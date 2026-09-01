# Guía del administrador de nspawnmgr

Esta guía recorre la puesta en marcha de un despliegue real, de producción, de nspawnmgr desde
cero: el host Linux y `systemd-nspawn`, la base de datos, Tomcat, Apache Guacamole, la aplicación
de inicio de sesión `auth` y el propio nspawnmgr. Asume un único host Linux de la familia
Debian/Ubuntu ejecutando todo, que es la disposición contra la que el propio proyecto se compila y
se prueba; adapte las rutas/nombres de paquete si usa una distribución diferente.

Para el ciclo de desarrollo local (simulaciones, sin contenedores reales, sin Guacamole real), vea
en su lugar `site/env/README.md` y `dev_env/README.md` — esta guía trata sobre un despliegue real.

## 1. Resumen de la arquitectura

**nspawnmgr se ejecuta desde una de sus propias máquinas systemd-nspawn** — un contenedor Debian
autoalojado llamado `nspawnmgr`, creado automáticamente por el `postinst` del `.deb`
(`nspawnmgr-bootstrap-app-machine.sh`) antes de que ningún administrador toque la aplicación.
Solo un conjunto pequeño y fijo de cosas permanece en el host desnudo:

| Permanece en el host | Por qué |
|---|---|
| `nspawnmgr_exec` (la cuenta SSH con capacidad sudo, [§3](#3-la-cuenta-ssh-con-capacidad-sudo)) | La creación/gestión de contenedores necesita root real en el host desnudo — esta es la única cuenta que lo tiene |
| Plantillas y paquetes (`/var/lib/nspawnmgr/templates`, la caché de paquetes del administrador) | Almacenamiento compartido, del lado del host, con el que se construye cada contenedor (incluido el del propio nspawnmgr) |
| `nspawnbr0` (el puente compartido) y dnsmasq | La red a la que se conecta cada contenedor, incluidos los autoalojados |

Todo lo demás — Tomcat, los cuatro WAR (`nspawnmgr.war`, `auth.war`, `guacamole.war`, `ROOT.war`)
y `guacd` — se ejecuta **dentro** de la máquina `nspawnmgr`, todo en una única instancia de
Tomcat 9 allí, cada uno en su propia ruta de contexto (`/nspawnmgr`, `/auth`, `/guacamole`, y `/`
para `ROOT.war`) exactamente como antes — solo ha cambiado *dónde* se ejecuta esa instancia de
Tomcat, no cómo están dispuestos los cuatro WAR entre sí. Vea el comentario al principio del
`pom.xml` raíz para saber por qué el propio nspawnmgr está fijado a Boot 2.7/Tomcat 9 (para
coincidir con la propia aplicación web de Guacamole, que no puede ejecutarse en Jakarta EE/Tomcat
10+ sin modificaciones) y el comentario al principio de `auth/pom.xml` para el mismo razonamiento
aplicado a `auth`.

Dado que la máquina `nspawnmgr` no tiene acceso a la red del host (solo un veth ordinario hacia
`nspawnbr0`, como cualquier otro contenedor), `postinst` también elige un puerto libre del host
(8080, o el siguiente libre — imprime cuál) y lo reenvía directamente al propio `:8080` de esa
máquina mediante una línea `Port=` en su archivo `.nspawn`, el mismo mecanismo que usan los
[mapeos de puertos personalizados](#mapeos-de-puertos-personalizados-y-acceso-saliente) para
contenedores ordinarios. Navegar a `http://<este host>:<ese puerto>/` por tanto sigue alcanzando
nspawnmgr exactamente como siempre lo hizo — el autoalojamiento es invisible desde el lado del
navegador.

El backend PAM de `auth.war` (el predeterminado — vea [§8](#8-auth-backend-de-inicio-de-sesión))
autentica contra el host en el que residan las cuentas del sistema operativo local de su propia
JVM. Dado que `auth.war` ahora se ejecuta dentro de la máquina `nspawnmgr`, eso significa que son
sus propias cuentas — creadas durante el [asistente de configuración del primer
arranque](#asistente-de-configuración-del-primer-arranque), no las del host desnudo — sin que se
necesite código de backend ni configuración para que eso sea así.

La base de datos también está autoalojada: el asistente de configuración del primer arranque
aprovisiona su propia máquina de base de datos Debian (vea [§4](#4-base-de-datos)) en lugar de
conectarse a un servidor ya existente. Tanto la máquina `nspawnmgr` como su máquina de base de
datos aparecen como contenedores ordinarios y visibles en la propia lista de contenedores de
nspawnmgr tan pronto como termina el asistente del primer arranque — vea la nota al respecto en
[§4](#4-base-de-datos). Ambas también están configuradas para
[iniciarse automáticamente cuando arranca el propio host](#inicio-automático-al-arrancar-el-host),
con `nspawnmgr` configurado para requerir que su máquina de base de datos ya esté iniciada — de lo
contrario, un reinicio del host podría arrancar la máquina `nspawnmgr` antes de que su máquina de
base de datos siquiera esté lista, dejándola en ejecución sin base de datos alcanzable hasta que
alguien lo notara e iniciara la otra máquina a mano.

nspawnmgr en sí nunca ejecuta `machinectl`/`systemd-run` directamente — la cuenta bajo la que se
ejecuta Tomcat no tiene sudo, dondequiera que Tomcat mismo se ejecute. En su lugar, nspawnmgr se
conecta por SSH a la **cuenta separada, con capacidad sudo, `nspawnmgr_exec` en el host desnudo** y
ejecuta allí comandos privilegiados como root — operaciones rutinarias (iniciar/detener/eliminar
un contenedor, sincronización del cortafuegos) sin necesitar nunca una contraseña, y solo las más
arriesgadas, solo en el momento de creación (que ejecutan contenido definido por la plantilla como
root dentro de un contenedor recién creado, o aprovisionan una máquina completamente nueva)
requieren una, obtenida ya sea de la configuración almacenada o de una aprobación del administrador
por solicitud. En una instalación empaquetada, esta conexión SSH apunta a la propia dirección fija
de `nspawnbr0` (`10.100.0.1`) en lugar de `127.0.0.1`, ya que nspawnmgr está alcanzando *hacia
afuera* al host desde dentro de su propia máquina en lugar de hablar consigo mismo — configurado
automáticamente por `nspawnmgr-bootstrap-app-machine.sh`, nada que configurar a mano. Configurar
esa cuenta es uno de los pasos más importantes y fáciles de pasar por alto más abajo
([§3](#3-la-cuenta-ssh-con-capacidad-sudo)).

## 2. Requisitos previos del host

En el host Linux que ejecutará los contenedores:

```bash
sudo apt update
sudo apt install -y systemd-container openssh-server
```

`systemd-container` proporciona `machinectl`, `systemd-nspawn` y `systemd-run` — incluyendo
`machinectl import-tar`, que nspawnmgr usa para clonar una plantilla de contenedor en una máquina
nueva (habla con `systemd-importd`, activado por socket de la misma manera que lo está
`systemd-machined` para `machinectl start`, así que debería funcionar sin ninguna configuración
adicional). Confirme que lo básico funciona:

```bash
machinectl list-images   # should run without error, even with an empty list
```

nspawnmgr espera que existan dos directorios y sean escribibles por la cuenta con capacidad sudo
(creados automáticamente por `systemd-nspawn`/`machinectl` la primera vez que se usan, pero vale
la pena confirmarlo):

- `/var/lib/machines` — donde viven los sistemas de archivos raíz de los contenedores
  (`NSPAWN_MACHINES_DIR`)
- `/etc/systemd/nspawn` — donde viven los archivos de configuración `.nspawn` por contenedor
  (`NSPAWN_SETTINGS_DIR`)

Estas son **rutas de sistema reales y fijas** — `machinectl`/`systemd-nspawn` nunca miran en
ningún otro sitio, sin importar lo que diga la propia configuración de nspawnmgr. No intente
aislarlas en un sandbox.

### Bases de datos (dos, independientes: una para nspawnmgr y otra para Guacamole)

Planifique **dos bases de datos independientes**, ambas en el mismo servidor MySQL/MariaDB o
PostgreSQL: el propio esquema de usuarios/contenedores/ajustes/plantillas de nspawnmgr, y el
propio esquema de usuarios/conexiones/permisos de Guacamole (gestionado por separado por la
extensión `guacamole-auth-jdbc` de Guacamole). **Solo MySQL/MariaDB o PostgreSQL — sin opción H2.**
Vea [§4](#4-base-de-datos) — el asistente de configuración del primer arranque crea ambas bases de
datos por usted, con nombres fijos y predeterminados (`nspawnmgr`/`guacamole`), así que no hay nada
que preparar a mano de antemano.

### Plantillas de contenedores (sistemas de archivos raíz base)

nspawnmgr aprovisiona contenedores nuevos clonando una "plantilla" en `/var/lib/machines` mediante
`machinectl import-tar`. Las propias plantillas viven bajo `TEMPLATES_DIR` (por defecto
`/var/lib/nspawnmgr/templates`), un subdirectorio por backend — `nspawn/`, `podman/` y `qemu/`
(vea ["Podman: pods"](#podman-pods) y ["QEMU: máquinas virtuales"](#qemu-máquinas-virtuales) más
abajo para los propios formatos de plantilla de los otros dos backends y cómo se puebla cada uno —
esta sección trata específicamente de los archivos `<nombre>.tar.gz` de nspawn: tars gzipeados
planos de un sistema de archivos raíz, exactamente lo que consume `machinectl import-tar`). Necesita
preparar usted mismo al menos una real y arrancable — nspawnmgr no las descarga ni las construye
por usted, con una excepción: `/admin/templates` ofrece tres botones independientes **"Configurar
X mínima"** — **debian-minimal** (APT), **fedora-minimal** (DNF), **arch-minimal** (PACMAN) — cada
uno se muestra solo mientras la plantilla de esa variante específica aún no exista (configurar una
no oculta las demás; configure una, varias o las tres). Cada uno descarga un minirootfs real
(verificado con checksum) desde images.linuxcontainers.org, instala y habilita un servidor SSH en
él, lo empaqueta como `TEMPLATES_DIR/nspawn/<variante>-minimal.tar.gz`, y lo registra con su
indicador "SSH preinstalado" activado — una plantilla real y funcional con un solo clic. Ese
indicador (también configurable en cualquier plantilla creada a mano, vea su formulario de edición)
le dice a la creación de contenedores que la imagen ya tiene SSH instalado y habilitado, saltándose
el paso de descarga/instalación/habilitación que de otro modo sería redundante y que toda otra
plantilla necesita. No es una herramienta general de gestión de plantillas: no hay un botón
equivalente para un nombre personalizado, y cada botón desaparece en cuanto existe la plantilla de
su propia variante específica (sin importar qué otras plantillas existan). Mismo requisito de sudo
que todo lo demás que solo ocurre en el momento de creación (§3) — en modo de aprobación del
administrador se le pedirá la contraseña de sudo en línea. Vea
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-{debian,fedora,arch}-template.sh` para
ver exactamente qué hace cada uno — **solo el de Debian se ha confirmado contra un contenedor
real**; vea ["Plantillas de Fedora y Arch: estado de
verificación"](#plantillas-de-fedora-y-arch-estado-de-verificación) más abajo para el estado de
verificación de los otros dos, y para el enfoque de doble vía (nativo del host frente a chroot) que
ahora comparten los tres scripts. Los propios
`site/templates/nspawn/{debian-minimal,fedora-minimal,arch-minimal,alpine-minimal}` del repositorio
son algo *diferente* — pequeños directorios de marcador de posición (ni siquiera tarballs) usados
solo para pruebas locales en modo desarrollo (vea `site/templates/README.md`) — **no los use como
plantillas reales**, no son arrancables.

Deliberadamente no hay una variante de Alpine entre las tres: el minirootfs oficial de Alpine no
tiene systemd/D-Bus en absoluto (usa OpenRC), y todo comando dentro del contenedor que nspawnmgr
ejecuta pasa por `systemd-run --machine=`, lo cual requiere que el propio contenedor esté
ejecutando systemd — un contenedor basado en Alpine falla con "Failed to connect to bus" de forma
permanente, no como una condición de arranque transitoria que valga la pena reintentar. Un soporte
real de Alpine necesitaría systemd instalado y funcionando como PID 1 dentro del contenedor
primero, lo cual no es estándar en Alpine y no está probado aquí.

#### Plantillas de Fedora y Arch: estado de verificación

**debian-minimal es el único de los tres botones "Configurar X mínima" confirmado contra un
contenedor real** — se ha creado y arrancado en vivo varias veces a lo largo de este proyecto.
**fedora-minimal** y **arch-minimal** permanecen específicamente sin verificar: existen hosts
reales de Fedora/Arch y se han usado extensamente en otras partes de este proyecto (vea las
secciones de instalación de paquetes RPM/Arch más arriba), pero `nspawnmgr-create-fedora-template.sh`/
`nspawnmgr-create-arch-template.sh` — los scripts que llaman estos dos botones específicos de la
interfaz de administración — nunca se han ejercitado realmente contra un contenedor systemd-nspawn
real. Si prueba cualquiera de los dos, por favor informe qué falla — algunas áreas de riesgo
específicas conocidas, aproximadamente en orden de probabilidad de que causen problemas:

- **Los tres scripts de horneado (Debian, Fedora, Arch) detectan la propia distribución del HOST
  y eligen una de dos rutas de instalación en consecuencia**, en lugar de asumir una distribución
  concreta. Cada script comprueba `command -v apt-get`/`dnf`/`pacman` para su PROPIO gestor de
  paquetes objetivo: si el host tiene uno que coincide, ejecuta esa herramienta como un
  **proceso normal del lado del host** apuntado al rootfs extraído (la combinación de apt
  `-o Dir=`/`-o DPkg::Options::=--root=`, `dnf --installroot=`, `pacman --root=`). Si el host no
  tiene ningún gestor de paquetes coincidente en absoluto (p. ej. nspawnmgr desplegado en un host
  Debian horneando una plantilla de Fedora o Arch, o viceversa), el script en su lugar **hace
  `chroot` en el rootfs recién extraído y usa la propia copia empaquetada de la herramienta que
  trae la imagen** — `/etc/resolv.conf` se copia dentro (chroot no comparte la configuración de
  red del host), `/dev`/`/proc`/`/sys`/`/run` se montan con bind antes de que se ejecute la
  instalación en el chroot (el montaje bind de `/run` en particular hace que el módulo NSS de
  `systemd-resolved` sea alcanzable para la resolución DNS dentro del chroot — sin él, la
  resolución de nombres puede fallar incluso con un `/etc/resolv.conf` correcto en su sitio), se
  desmontan de nuevo inmediatamente después, antes de que se empaquete el tarball — la misma
  técnica que usan las propias etapas de chroot de `pacstrap`/`arch-chroot`/`debootstrap`. Solo
  la rama del lado del host del script de Debian (Debian sobre Debian) se ha ejercitado realmente
  contra un contenedor real; el respaldo de chroot del script de Debian, y ambas ramas de los
  scripts de Fedora/Arch, están construidos según la especificación pero sin verificar — estos
  scripts específicos de horneado de plantillas de contenedor nunca se han ejecutado de verdad,
  aunque existen hosts reales de Fedora/Arch y se usan en otras partes de este proyecto.
- **arch-minimal es el más especulativo de los tres.** Áreas de riesgo conocidas: (1) el archivo
  `/etc/pacman.d/mirrorlist` de la imagen descargada llega con todos los servidores espejo
  comentados por convención propia de Arch — el script escribe `geo.mirror.pkgbuild.com` (el
  redirector GeoIP oficial de Arch) explícitamente; (2) la verificación de firmas de paquetes
  necesita un keyring poblado que este script no configura (el `pacstrap` real sí lo hace, vía
  `pacman-key --init`/`--populate`) — en lugar de intentarlo a ciegas sin forma de probarlo, el
  script deshabilita la verificación de firmas (`SigLevel = Never` en el `pacman.conf` de destino)
  para esta instalación de arranque, una compensación de seguridad real que vale la pena conocer
  aunque sea razonable para una plantilla rápida de desarrollo/pruebas; (3) la rama de chroot
  también deshabilita `CheckSpace` en `pacman.conf` — la comprobación de espacio en disco de
  pacman resuelve el directorio de caché a un punto de montaje vía `/proc/self/mountinfo`, que
  dentro de un chroot sigue reflejando las propias rutas absolutas del host en lugar del `/`
  remapeado del chroot, así que la comprobación falla con un engañoso "no hay suficiente espacio
  libre en disco" sin importar el espacio real disponible (una limitación conocida de pacman en
  chroot); (4) `pacman.conf` también recibe `DisableSandbox` añadido — el sandboxing de descargas
  de pacman basado en Landlock (más un usuario `alpm` dedicado sin privilegios al que cambia)
  queda bloqueado por el filtro seccomp predeterminado de `systemd-nspawn` en cuanto un contenedor
  realmente arranca y ejecuta `pacman` en vivo (a diferencia del propio `chroot` del lado del host
  de este script, que no tiene restricciones seccomp en absoluto) — cada invocación de `pacman`
  dentro de un contenedor real en ejecución necesita esto para funcionar en absoluto, no solo el
  propio paso de horneado de este script.
- **RDP no está disponible en absoluto para `arch-minimal`.** Confirmado en vivo: `xrdp`/`xorgxrdp`
  se han eliminado de los repositorios oficiales de Arch (`pacman -Ss xrdp` no encuentra ninguno,
  en un espejo recién sincronizado y completamente poblado — no es un problema de caché obsoleta
  ni de espejo incorrecto) y esta aplicación no tiene soporte de AUR al que recurrir.
  `arch-minimal` establece su propio estado de RDP como "no capaz" por defecto (vea el selector
  "RDP" de la página de administración de Plantillas), que es lo que en realidad deshabilita la
  opción "Habilitar RDP" en el formulario de Nuevo Nspawn para él — actívelo de nuevo a mano solo
  si una futura versión de Arch restaura el paquete, o si el propio comando de instalación de la
  plantilla se edita a mano para algo que funcione (p. ej. el propio `krdp` de KDE, todavía en
  `extra`, pero ligado específicamente a KDE/Plasma).
- **Todo contenedor de Fedora necesita que se omita su comprobación PAM de fase de cuenta de
  `sshd` para ser alcanzable por SSH en absoluto.** Todo intento de inicio de sesión SSH con clave
  pública en un contenedor de Fedora real y arrancado (confirmado tanto en 43 como en 44 — no es
  específico de la versión) se rechaza con `Access denied for user <cuenta> by PAM account
  configuration [preauth]` (la fase de cuenta de `pam_unix`, `pam_acct_mgmt`, devuelve
  `PAM_AUTHINFO_UNAVAIL`) — la cuenta, su contraseña y su `authorized_keys` son todos genuinamente
  correctos; el propio `unix_chkpwd` (el ayudante setuid al que `pam_unix` recurre para leer
  `/etc/shadow` de forma segura) se niega a ejecutarse con "This binary is not designed for
  running in this way" — alguna comprobación de legitimidad del llamador en el `shadow-utils`
  actual de Fedora que no tolera ejecutarse dentro de un contenedor `systemd-nspawn`. `UsePAM no`
  en `sshd_config` **no** evita esto — confirmado en vivo, el propio proceso monitor privilegiado
  de sshd sigue llamando a `do_pam_account` de todos modos en esta compilación (el propio sshd
  advierte `'UsePAM no' is not supported in this build`). La corrección que sí funciona: el script
  apunta la propia fase de cuenta de `sshd` a `pam_permit.so` (siempre tiene éxito) en lugar de al
  `pam_unix.so` de `password-auth`, solo en `/etc/pam.d/sshd` — no un cambio de PAM en todo el
  sistema. Esto elimina las comprobaciones de *fase de cuenta* de PAM (expiración, `nologin`,
  etc.) específicamente para SSH; la comprobación de identidad real (verificación de clave
  pública) ya tiene éxito de forma independiente antes de que esta fase se ejecute nunca, así que
  es una compensación estrecha y deliberada para estas cuentas de administrador aprovisionadas y
  desechables. Confirmado funcionando en vivo en Fedora 43; la versión se mantiene fijada en 43
  (no la más nueva 44) simplemente porque esa es la combinación exacta verificada de extremo a
  extremo, no porque la 44 sea peor por lo demás.
- **El prompt SSH de todo contenedor de Fedora y Arch estaba lleno de texto literal de secuencia
  de escape** — `start=<uuid>;machineid=<uuid>;user=...;hostname=...;bootid=<uuid>;pid=...;type=shell;cwd=...`
  en lugar de un simple `[usuario@host ~]$`. Causa raíz (confirmada en vivo en Fedora; Arch mostró
  el mismo síntoma y comparte la misma causa raíz, ya que no es una peculiaridad específica de
  Fedora — solo cualquier distribución cuyo systemd sea lo bastante nuevo para incluirlo, ambas lo
  son aquí): systemd 257+ incluye `/usr/lib/systemd/profile.d/80-systemd-osc-context.sh`
  (enlazado simbólicamente en `/etc/profile.d/` por `systemd-tmpfiles`), que emite una secuencia
  de escape OSC 3008 "Hierarchical Context Signalling" en cada prompt; el propio emulador de
  terminal de Guacamole no la reconoce/elimina, así que se imprime como texto literal. El script
  solo se salta a sí mismo cuando `$TERM` no está definido o es `dumb` (vea su propio comentario
  de cabecera), y el cliente SSH de Guacamole informa un `$TERM` real, así que siempre se dispara.
  Se deshabilitó de la manera documentada (el propio comentario de cabecera del script da este
  procedimiento exacto) en ambos scripts de horneado: eliminar el enlace simbólico de
  `/etc/profile.d/` y enmascarar el fragmento de `tmpfiles.d` que lo vuelve a crear.
- **Instalar el gestor de escritorio Xfce en un contenedor de Fedora fallaba directamente** —
  `dnf group install -y "Xfce Desktop"` daba error con `No match for argument: Xfce Desktop`.
  Confirmado en vivo: a diferencia de GNOME/KDE, "Xfce Desktop" no es un grupo comps en el Fedora
  actual en absoluto (`dnf group list --available` no lo lista) — Fedora en su lugar distribuye un
  paquete plano con nombre, `xfce4`, que arrastra todo el escritorio. Se cambió a un simple
  `dnf install -y xfce4`, lo cual también hace que Xfce-en-DNF se pueda predescargar (vea
  "Instalación de paquetes: primero se descargan" más arriba) — a diferencia de las propias
  instalaciones de grupo comps de GNOME/KDE, que todavía no se pueden predescargar y todavía
  necesitan que funcione la propia red/DNS del contenedor. De paso, se amplió ese mismo mecanismo
  de predescarga de solo APT a APT/DNF/PACMAN en general (los scripts de descarga subyacentes ya
  admitían los tres; solo la condición que decidía si usarlos seguía siendo solo APT) — los
  nombres de paquete de SSH/RDP/VNC ahora también se resuelven por gestor de paquetes (p. ej. el
  paquete SSH de Arch es `openssh`, no `openssh-server`; su instalación de RDP además necesita
  `xorgxrdp`).
- **Esa ampliación de la predescarga entonces rompió por completo la creación de contenedores de
  Fedora/Arch** — `Failed to download DNF packages [openssh-server] ... dnf: not found`, y el
  mismo fallo idéntico para PACMAN. Confirmado en vivo en ambos. Causa raíz:
  `nspawnmgr-download-packages-dnf.sh`/`-pacman.sh` (y sus hermanos de simulación de instalación,
  usados por el flujo de subida de Paquetes del administrador) ejecutaban `dnf`/`pacman`
  directamente en el *host* (`--installroot=`/`--root=` apuntado al rootfs del contenedor) —
  funciona para APT, ya que el `.deb` de este proyecto solo apunta a hosts Debian/Ubuntu, que
  siempre tienen `apt-get`, pero ni `dnf` ni `pacman` están nunca en el propio `PATH` de un host
  así en absoluto. A diferencia del *horneado* de plantillas (que puede recurrir a un `chroot` del
  lado del host en un rootfs aún no arrancado), un contenedor real ya en ejecución no se puede
  poner en chroot de forma segura de la misma manera — la corrección en su lugar ejecuta
  `dnf`/`pacman` *dentro* del propio contenedor vía `systemd-run --machine=`, la misma primitiva
  de ejecución no interactiva dentro del contenedor que ya usa el paso de instalación real —
  solo descarga, así que sigue sin tocar el estado de paquetes instalados. Compensación:
  DNF/PACMAN pierden la reutilización propia de APT de "un paquete ya en caché y válido nunca se
  vuelve a descargar" entre contenedores, ya que el directorio de caché compartido del lado del
  host no es visible desde dentro del propio espacio de nombres de montaje de un contenedor — cada
  predescarga de DNF/PACMAN vuelve a descargar todo desde cero.
- **La corrección dentro del contenedor de arriba aún falló en el primer reintento en vivo** —
  dnf5 rechaza `--destdir` en `install` directamente (`Unknown argument "--destdir=..." for
  command "install" ... available for: reposync, download, upgrade`); la combinación
  `install --downloadonly --destdir=` de dnf4 no se traslada. El propio comando de dnf5 para
  descargar sin instalar es `download`, y por defecto solo obtiene el paquete o paquetes
  *nombrados*, no sus dependencias — `--resolve` es lo que trae toda la cadena de dependencias
  también, el equivalente real de dnf5 a lo que proporcionaba `install --downloadonly`.
  Corregido: `dnf download --resolve --destdir=<dir> <paquetes>`. Misma lección que los errores de
  `groupinstall`→`group install`/EPEL-en-Fedora de arriba: la superficie de la CLI de dnf5 difiere
  de la de dnf4 de formas reales y no obvias — confirme en vivo en lugar de asumir que la sintaxis
  de la era de dnf4 se traslada sin más.
- Ambos scripts también traducen el nombre de arquitectura de `uname -m` (`x86_64`/`aarch64`) a la
  propia convención de images.linuxcontainers.org (`amd64`/`arm64`) antes de construir la URL —
  omitir esa traducción da un 404 sin importar que la versión/build por lo demás sea correcta.
- Ambos scripts reutilizan los mismos fragmentos de systemd-networkd para
  `net.ipv4.ping_group_range`/dominio DNS que necesita el script de Debian — se trata de la propia
  configuración de red de contenedores generada por systemd-nspawn, no de nada específico de
  Debian, así que *deberían* trasladarse a cualquier rootfs basado en systemd, pero eso es una
  suposición, no un hecho confirmado en vivo, específicamente para Fedora/Arch.

El propio predescargado de dependencias DNF del flujo manual de "Instalar paquete" (simulación vía
`dnf install --assumeno`, obtención vía `dnf install --downloadonly`) conlleva la misma advertencia
idéntica de no verificado hasta probarse — vea "Subir e instalar paquetes arbitrarios" más arriba.

Alternativamente, construya una plantilla Debian a mano vía `debootstrap` (la misma idea de
obtención de rootfs, si prefiere no descargar desde images.linuxcontainers.org, o quiere una
versión/arquitectura diferente) — hornéela en un directorio de trabajo temporal, y luego
empaquétela en la ubicación real de `TEMPLATES_DIR` como un tar gzipeado:

```bash
SCRATCH=/tmp/debian-minimal-bake
sudo debootstrap --arch=amd64 bookworm "$SCRATCH" http://deb.debian.org/debian
# Bake in an SSH server so nspawnmgr's post-create SSH provisioning step can reach the container.
# Point apt at the tree instead of chrooting/booting into it to run apt-get directly: that runs
# apt's network traffic (including DNS resolution) from inside the container's own environment,
# which is unreliable on some hosts. This way apt runs as a normal host process using the host's
# own working network, and only chroots (via dpkg) for the final unpack/configure step.
# apt's official --root= flag is the "correct" way to do this, but isn't reliably supported across
# apt versions/builds - on at least one real host (apt 2.8.3, Linux Mint 22.1) it's rejected
# outright, even as root, even alone with no other options. -o Dir=/-o Dir::State::status= (apt's
# own package resolution) PLUS -o DPkg::Options::=--root= (forces the dpkg *subprocess* apt spawns
# to also chroot there for unpack/configure) reproduces the same behavior more portably. Without
# that DPkg::Options push-through, dpkg validates the downloaded packages against the HOST's own
# dpkg database instead of the target tree's, causing spurious dependency conflicts if the host
# isn't running the same distro/release as the template.
# -o APT::Sandbox::User=root: without this, apt drops privileges to the unprivileged '_apt' user
# for the actual download step, which fails ("couldn't be accessed by user '_apt' ... Permission
# denied") because the freshly-extracted tree's apt/dpkg directories are only writable by root.
APT_OPTS="-o Dir=$SCRATCH -o Dir::State::status=$SCRATCH/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=$SCRATCH"
sudo env DEBIAN_FRONTEND=noninteractive apt-get $APT_OPTS update
sudo env DEBIAN_FRONTEND=noninteractive apt-get $APT_OPTS install -y openssh-server
sudo chroot "$SCRATCH" systemctl enable ssh
# Pack into TEMPLATES_DIR as a machinectl import-tar-compatible gzipped tar, then discard the
# scratch directory - the tarball is the only thing nspawnmgr (or machinectl) ever reads.
sudo mkdir -p /var/lib/nspawnmgr/templates/nspawn
sudo tar -czf /var/lib/nspawnmgr/templates/nspawn/debian-minimal.tar.gz --numeric-owner -C "$SCRATCH" .
sudo rm -rf "$SCRATCH"
```

Cada archivo `.tar.gz` bajo `TEMPLATES_DIR/nspawn/` es una plantilla seleccionable; registre/edite
la fila `Template` correspondiente en `/admin/templates` (solo administrador) — nombre,
identificador de origen (el nombre de archivo desnudo, sin `.tar.gz`, sin prefijo de carpeta de
backend — p. ej. `debian-minimal` para `TEMPLATES_DIR/nspawn/debian-minimal.tar.gz`), backend,
gestor de paquetes, y anulaciones opcionales de comando de instalación. Cada plantilla tiene un
**backend** (`domain/ContainerBackend.java`: `SYSTEMD_NSPAWN`, `PODMAN`, o `QEMU`) registrado
contra ella, cada uno con su propio subdirectorio y formato de archivo de `TEMPLATES_DIR` — vea las
secciones más abajo para los de Podman y QEMU. Una instalación nueva empieza con **cero**
plantillas — no se siembra ninguna — así que esta página (o el botón "Configurar debian-minimal" de
abajo) es realmente cómo se obtiene la primera; el propio tarball bajo `TEMPLATES_DIR` de todos
modos se tiene que preparar fuera de banda como se muestra arriba, la página solo gestiona los
metadatos que apuntan a él. Desactivar una plantilla (en lugar de eliminarla) es la forma normal de
retirarla — desaparece del desplegable de creación de contenedores pero los contenedores existentes
construidos a partir de ella no se ven afectados; eliminarla solo se permite una vez que ningún
contenedor la referencia. Vea la sección "Límite de confianza" de [§3](#3-la-cuenta-ssh-con-capacidad-sudo)
para saber qué protege realmente el filtro de solo-administrador de esta página.

**Las plantillas también se pueden crear a partir de una máquina existente**, no solo descargarse
nuevas: la propia página de detalle de un contenedor detenido tiene un campo "Crear plantilla a
partir de esta máquina" (nombre + descripción opcional). Empaqueta el rootfs actual de esa máquina
(`tar -czf`, la misma convención que ya produce cada script de horneado de arriba) en una plantilla
nueva e independiente — útil para tomar una instantánea de un contenedor que un propietario ya ha
personalizado en lugar de volver a aprovisionar desde cero. Deliberadamente solo se ofrece mientras
la máquina está **DETENIDA**: empaquetar un rootfs en vivo arriesga un archivo inconsistente
mientras los archivos cambian a mitad del tar. A diferencia de la página "Nueva plantilla"/
"Configurar X mínima", que es solo de administrador, esta es una acción del propietario del
contenedor (`/api/containers/{id}/create-template`, no bajo `/api/admin/**`) — la plantilla
resultante es por lo demás idéntica, incluyendo el mismo requisito de contraseña de sudo, y
posteriormente puede ser usada por cualquiera igual que cualquier otra plantilla. Al igual que el
endpoint "Instalar paquete", esto solo funciona hoy en modo de secreto almacenado (siempre pasa una
anulación de contraseña de sudo nula) — el modo de aprobación del administrador aún no está
conectado para esta acción.

El campo de nombre de origen del formulario "Nueva plantilla"/"Editar plantilla" sugiere los
nombres desnudos de cada archivo `.tar.gz` ya presente bajo el subdirectorio del backend
seleccionado de esa plantilla (obtenido de
`GET /api/admin/templates/available-source-files?backend=...`, respaldado por
`nspawnmgr-list-template-files.sh` — un script envoltorio de solo lectura y NOPASSWD como
`nspawnmgr-list-machine-images.sh`), así que no tiene que recordar un nombre de archivo exacto que
preparó fuera de banda. Es un `<datalist>` del navegador, no un desplegable estrictamente
restringido — el campo todavía acepta texto libre, ya que la lista de sugerencias es de mejor
esfuerzo (vacía si el host SSH no es alcanzable o el directorio aún no tiene nada) y no debería
bloquear el registro de los metadatos de una plantilla antes de que el tarball realmente llegue al
disco.

**Cambio disruptivo:** el almacenamiento de plantillas cambió de un árbol de directorios extraído
en vivo en `TEMPLATES_DIR/<nombre>` (clonado vía `cp -a`) a un tar gzipeado en
`TEMPLATES_DIR/nspawn/<nombre>.tar.gz` (clonado vía `machinectl import-tar`). Una fila `Template`
creada antes de este cambio apunta a una ubicación que nspawnmgr ya no reconoce — elimínela y
vuelva a crearla (p. ej. vuelva a hacer clic en "Configurar debian-minimal") o empaquete
manualmente cualquier plantilla personalizada colocada a mano en la nueva ubicación/formato como
se muestra arriba.

#### Instalación/actualización de plantillas desde un pipeline de CI/CD

Para la gestión programática de plantillas (un pipeline de CI/CD que construye y distribuye sus
propias plantillas) en lugar de un humano haciendo clic en `/admin/templates`, nspawnmgr distribuye
una CLI invocada por SSH en lugar de una API web — esta aplicación no tiene ninguna autenticación
HTTP de máquina a máquina en absoluto (tanto la autenticación básica como el inicio de sesión por
formulario están explícitamente deshabilitados; el único camino de inicio de sesión es la cookie
de sesión respaldada por su servicio de identidad externo), así que un endpoint HTTP orientado a
CI significaría inventar un nuevo mecanismo de autenticación desde cero. La CLI reutiliza en su
lugar el modelo de confianza SSH+sudo ya existente de este proyecto.

Esto usa una cuenta con capacidad sudo **segunda y deliberadamente aislada**, `nspawnmgr_ci` —
separada de `nspawnmgr_exec` (vea la sección "Límite de confianza" más abajo para saber por qué).
No existe hasta que usted lo activa:

```bash
sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
```

Esto crea la cuenta, bloquea el inicio de sesión por contraseña (autenticación solo por clave), e
imprime una clave SSH **privada** recién generada en stdout exactamente una vez — cópiela en el
propio almacén de secretos de su sistema de CI de inmediato; nada se queda en el host más allá de
la mitad pública. Vuelva a ejecutar con `--rotate-key` para reemplazarla más tarde (la clave
antigua deja de funcionar de inmediato, no queda rondando como una segunda credencial válida).

Desde su pipeline de CI/CD, instale o actualice una plantilla (upsert, con clave en `--name`)
enviando el tarball por SSH:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-template.sh \
  --name my-template --package-manager APT --description "Built by CI" \
  < my-template.tar.gz
```

`--name` pasa a formar parte de una ruta del sistema de archivos (`TEMPLATES_DIR/nspawn/<name>.tar.gz`)
y se valida en consecuencia (solo letras, dígitos, `-`, `_`). `--package-manager` es obligatorio
(`APT`, `DNF`, `APK`, o `PACMAN`); `--backend`, `--description`, `--install-ssh-command`,
`--install-xrdp-command`, `--rdp-capable`, `--active` son todos opcionales, coincidiendo con los
propios campos y valores predeterminados del formulario de administración. El tarball nuevo/
actualizado solo se intercambia en su sitio después de que se confirma la fila de la base de
datos, así que un fallo a mitad de camino nunca deja una plantilla a medio instalar — una
actualización en curso deja la versión anterior sirviendo hasta que la nueva esté completamente
lista.

#### Instalación/actualización de paquetes desde un pipeline de CI/CD

La misma cuenta `nspawnmgr_ci` (sin ningún paso de activación separado más allá del de arriba)
también puede publicar directamente en la [caché de paquetes del
administrador](#subir-e-instalar-paquetes-arbitrarios), para un pipeline de CI que construye sus
propios artefactos `.deb`/`.rpm`/etc. y quiere que estén disponibles para que los propietarios de
contenedores los instalen sin que un humano los suba a mano:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-package.sh \
  --package-manager APT --filename my-tool_1.2.3_amd64.deb --description "Built by CI" \
  < my-tool_1.2.3_amd64.deb
```

`--package-manager` (`APT`/`DNF`/`APK`/`PACMAN`/`ISO` — vea [Medios
extraíbles](#medios-extraíbles-imágenes-iso) para saber qué significa `ISO` aquí) y `--filename`
son obligatorios (este último no puede contener `/` ni empezar con `.`); `--description` es
opcional. Instalar-o-actualizar (upsert) tiene como clave `--package-manager` + `--filename` juntos
— volver a ejecutar con los mismos dos reemplaza el archivo anterior y actualiza su fila en su
sitio, la misma postura de seguridad ante fallos que las instalaciones de plantillas (la escritura
en la base de datos se confirma antes de que se reemplace el archivo antiguo en disco). Dado que
`cached_packages` requiere una cuenta de quien sube real (`uploaded_by_user_id`), el primer paquete
instalado por CI autoaprovisiona un pseudo-usuario dedicado `nspawnmgr-ci` — mostrado como quien
subió el archivo en la página de administración y en la sección "Instalar paquete" de cada
contenedor, exactamente igual que lo estaría el propio nombre de usuario de un administrador
humano.

### Reiniciar contenedores

La página de detalle de un contenedor en ejecución tiene un botón **Reiniciar** junto a
Detener/Forzar detención. Ejecuta `machinectl reboot` — un reinicio limpio y en el sitio del propio
sistema operativo del contenedor, a diferencia de Detener+Iniciar: el registro de la máquina y su
interfaz veth nunca se destruyen y recrean, así que los mapeos de puertos personalizados, el
estado del cortafuegos de acceso saliente, y cualquier otra cosa ligada a ese veth siguen siendo
válidos sin necesitar una resincronización. El contenedor pasa por el mismo estado ARRANCANDO que
un inicio nuevo mientras `ContainerReadinessPollingService` espera a que SSH (y RDP, si está
habilitado) vuelva a estar activo.

### Pausar y reanudar contenedores

La página de detalle de un contenedor en ejecución tiene botones **Pausar**/**Reanudar** junto a
Detener/Forzar detención. A diferencia de Detener, nada se destruye: Pausar ejecuta
`systemctl freeze` contra la propia unidad `systemd-nspawn@<nombre>.service` del contenedor,
suspendiendo en su sitio cada proceso de su cgroup mediante el congelador de cgroups del kernel
(systemd 246+); Reanudar ejecuta `systemctl thaw` para revertirlo, retomando exactamente donde se
quedó. `machinectl` en sí no tiene ningún concepto nativo de pausa/reanudación — este es el
equivalente moderno y nativo de systemd, el mismo mecanismo que ya proporcionan
`systemctl freeze`/`thaw` para cualquier otro tipo de unidad.

Un contenedor iniciado vía `machinectl start` (que es como nspawnmgr siempre los inicia) se ejecuta
como la unidad `systemd-nspawn@<nombre>.service` directamente, sin un `machine-<nombre>.scope`
separado — esa unidad de servicio es lo que apuntan Pausar/Reanudar. freeze/thaw funcionan contra
cualquier unidad con un cgroup, incluidas las unidades de servicio. El propio *comportamiento* de
freeze/thaw (si el controlador de congelación está disponible/habilitado, si los procesos
genuinamente se suspenden/reanudan correctamente) todavía vale la pena confirmarlo empíricamente si
depende de esto en gran medida.

### Inicio automático al arrancar el host

La página de detalle de un contenedor GESTIONADO (no se muestra para hosts EXTERNOS, que no tienen
una imagen `machinectl` propia que habilitar) tiene un panel **Ajustes de la máquina** con dos
campos:

- **Iniciar automáticamente al arrancar el host** — una casilla de verificación respaldada por
  `systemctl is-enabled`/`enable`/`disable` en la propia unidad
  `systemd-nspawn@<nombre>.service` del contenedor.
- **Requiere que esta máquina ya esté iniciada** — un desplegable con el nombre de cualquier otro
  contenedor GESTIONADO, respaldado por un fragmento de unidad systemd en
  `/etc/systemd/system/systemd-nspawn@<nombre>.service.d/nspawnmgr-requires.conf`
  (`Requires=`/`After=` contra la propia unidad de la máquina elegida, con `systemctl daemon-reload`
  ejecutado después de cada cambio). Solo tiene sentido junto con el inicio automático de arriba —
  controla el *orden* de arranque entre dos máquinas que ambas se inician por sí solas, no una
  dependencia en tiempo de ejecución que Detener/Iniciar impongan de otro modo.

Ambos campos se **leen en vivo desde el host en cada carga de página, no se almacenan en la propia
base de datos de nspawnmgr** — deliberadamente, ya que nada impide que un administrador ejecute
`systemctl enable`/`disable` directamente en el host fuera de nspawnmgr, y un valor en caché podría
desviarse silenciosamente de lo que `systemd` realmente tiene configurado. Un fallo transitorio de
SSH al leerlos muestra un mensaje de respaldo en la página en lugar de fallar del todo; guardar un
cambio pasa por los mismos dos scripts envoltorio que la lectura
(`nspawnmgr-set-machine-autostart.sh`/`nspawnmgr-set-machine-requires.sh`, ambos NOPASSWD —
rutinarios, iniciados por el propietario, del mismo nivel que Iniciar/Detener).

**La máquina autoalojada `nspawnmgr` y su máquina de base de datos** (vea
[§1](#1-resumen-de-la-arquitectura)) están ambas configuradas para iniciarse automáticamente de
esta manera, con `nspawnmgr` configurado para requerir su máquina de base de datos — de lo
contrario, un reinicio del host podría hacer que `nspawnmgr` arranque antes de que su propia base
de datos sea alcanzable. Esto está conectado por
`ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()` (el mismo paso de
reconciliación de infraestructura autoalojada que también vincula ambas máquinas a la plantilla
`debian-minimal`, aprovisiona su acceso SSH gestionado, y establece la descripción de cada una en
la lista de contenedores — vea [§1](#1-resumen-de-la-arquitectura) y ["Descubrir máquinas creadas
fuera de nspawnmgr"](#descubrir-máquinas-creadas-fuera-de-nspawnmgr)), que se ejecuta según su
propia programación recurrente de ~30s desde el momento en que arranca la propia aplicación Spring
de nspawnmgr — no depende de ninguna acción del administrador. Un fallo transitorio (registrado en
WARN, nunca fatal) simplemente se retoma en el siguiente paso, sin necesitar acción del
administrador; la misma reconciliación también se sigue ejecutando como parte de un clic manual en
**Descubrir máquinas**.

### Redes de contenedores

Todo contenedor gestionado comparte un puente, `nspawnbr0` (`Bridge=nspawnbr0` en el archivo
`.nspawn` generado — `machinectl start` esclaviza automáticamente el propio veth de cada
contenedor en él al iniciar), en lugar de que cada uno reciba un veth punto a punto aislado en su
propia subred privada. `nspawnbr0` y su dirección (`10.100.0.1/24`, fija y no configurable por el
administrador — una convención interna, no un punto real de personalización) se crean
incondicionalmente por el propio postinst del `.deb`
(`/etc/systemd/network/70-nspawnmgr-bridge.netdev`/`.network`), no es algo que usted configure a
mano. **Diagnóstico de red** tiene una comprobación de solo lectura que confirma que realmente
está activo.

**SSH/RDP/VNC no necesitan ningún reenvío entrante en absoluto.** El `guacd` de Guacamole y la
propia comprobación de disponibilidad de nspawnmgr marcan directamente la dirección veth interna
de un contenedor GESTIONADO (su interfaz `host0`, resuelta en vivo vía `machinectl`/`nsenter` —
vea `nspawnmgr-get-internal-address.sh`), en el puerto sshd/xrdp/VNC real del contenedor
(22/3389/5900). No hay ningún reenvío de puerto del host en el bucle en absoluto para estos, lo
cual evita una limitación de NAT en horquilla en el mismo host confirmada en hardware real: el
tráfico desde el propio host de vuelta a través de su propia dirección DNAT'd/reenviada a un
contenedor con frecuencia no se re-NATea correctamente, aunque un cliente genuinamente externo que
alcance esa misma dirección+puerto funcione bien. La dirección interna asignada al contenedor se
registra (en INFO) en el momento en que alcanza EN EJECUCIÓN, y se resincroniza con la
configuración de conexión de Guacamole en cada reinicio posterior en caso de que la dirección
cambie.

### Acceso gráfico: RDP, VNC y gestores de escritorio

El formulario "Nuevo Nspawn" tiene dos casillas de verificación independientes, **Habilitar RDP**
y **Habilitar VNC** — cualquiera de las dos, ambas, o ninguna. Elegir cualquiera revela un
desplegable **Gestor de escritorio** (Ninguno/GNOME/KDE (`kde-standard`)/Xfce (`xfce4`)): un
protocolo gráfico es de uso limitado sin un entorno de escritorio real dentro de una plantilla
mínima, así que elegir uno lo instala durante el aprovisionamiento, compartido entre RDP y VNC si
se eligen ambos. **Ninguno** significa que no se instala nada adicional.

A diferencia del acceso por solicitud de credenciales cubierto más abajo, RDP/VNC elegidos en el
momento de la creación reciben una cuenta/contraseña real generada que nspawnmgr crea y almacena
(RDP reutiliza la cuenta SSH con una contraseña de inicio de sesión establecida vía `chpasswd`;
VNC reutiliza la misma cuenta pero solo establece una contraseña específica de VNC vía
`vncpasswd` — no necesita ninguna contraseña de inicio de sesión de Linux propia). La secuencia
exacta de `vncserver`/`xstartup`/instalación de paquetes solo se ha ejercitado contra la única
plantilla real `debian-minimal` (APT) en uso activo — vale la pena volver a confirmarlo después de
instalar un `.deb` que incluya esto.

### Podman: pods

Junto a los contenedores nspawn, el menú "+" **Nuevo Pod** crea un contenedor real ejecutado con
`podman` (insignia `PODMAN` en la cuadrícula de Máquinas, junto a `NSPAWN`/`QEMU`/`HOST`) — las
mismas reglas de propiedad/compartición, la misma cuadrícula de tarjetas, la misma relación de
página de detalle que todo lo demás aquí. Está disponible para cualquier usuario que haya iniciado
sesión, no restringido a administradores; el enlace solo se deshabilita mientras no existan aún
plantillas con backend podman, la misma postura que Nuevo Nspawn.

**Creación** (`/containers/new-pod`): Nombre, Plantilla (un desplegable solo de plantillas con
backend podman), Descripción, y un Comando opcional — como una anulación de `CMD` de un
Dockerfile; dejarlo en blanco confía en el propio comando integrado de la imagen. Un shell
interactivo desnudo como comando saldrá en cuestión de momentos en cuanto no quede nada conectado
a su stdin, dejando el pod DETENIDO en lugar de fallido — vale la pena saberlo si un primer pod
parece desaparecer inmediatamente después de la creación. El aprovisionamiento
(`ProvisioningService.provisionPod()`) carga la imagen de la plantilla, crea e inicia el
contenedor, concede acceso al propietario, resuelve y persiste su dirección interna, y lo deja
directamente en **EN EJECUCIÓN** — a diferencia de los contenedores nspawn, no hay ninguna fase de
`ARRANCANDO`/sondeo de disponibilidad, ya que `podman create`+`start` son síncronos y un pod no
recibe ninguna credencial SSH autoaprovisionada por la que sondear en primer lugar.

**Redes**: los pods comparten el mismo puente `nspawnbr0` que los contenedores nspawn, pero a
través de una definición de red podman dedicada (`/etc/containers/networks/nspawnbr0.json`,
escrita por `nspawnmgr-configure-podman-network.sh`) usando el **IPAM host-local** de netavark en
lugar de DHCP — el propio proxy DHCP de netavark transmite desde el espacio de nombres de red del
host, y el kernel nunca hace que ese tráfico vuelva a la propia cola de recepción del puente, un
callejón sin salida confirmado en lugar de una opción sin explorar. El grupo de direcciones está
separado del propio rango DHCP de nspawn para evitar colisiones: los pods reciben
`10.100.0.192`–`10.100.0.254`, los contenedores nspawn mantienen `10.100.0.2`–`10.100.0.191`. El
DNS se establece explícitamente en la creación (`podman create --dns 10.100.0.1 --dns-search
internal ...`) en lugar de depender de cualquier configuración entregada por DHCP que un pod nunca
recibe — el propio `aardvark-dns` de podman está deshabilitado en esta red específicamente para
evitar chocar con el propio dnsmasq de nspawnmgr, ya vinculado a esa misma dirección (vea
["Resolución de contenedores por nombre"](#resolución-de-contenedores-por-nombre) más arriba).

**El ciclo de vida** tiene paridad total con los contenedores nspawn — Iniciar/Detener/Reiniciar/
Pausar/Reanudar se despachan todos a comandos nativos de podman (`start`/`stop`/`kill`/`restart`/
`pause`/`unpause`) en lugar de a cualquier mecanismo específico de nspawn. Un
**`ContainerLivenessPollingService`** separado vuelve a comprobar el estado real de podman de
cada pod EN EJECUCIÓN (y el estado real de unidad de cada VM de QEMU EN EJECUCIÓN — vea más abajo)
según su propia programación de ~30s y cambia el propio estado de nspawnmgr a DETENIDO en el
momento en que la realidad discrepa — necesario porque un pod puede salir por completo por sí
mismo (un comando de mantenimiento en vida malo o ausente, vea el campo Comando de arriba) sin que
nada más en la aplicación se dé cuenta nunca, ya que los pods se saltan por completo la ruta de
sondeo de disponibilidad exclusiva de nspawn. Los pods PAUSADOS no se sondean.

**Acceso**: SSH/RDP/VNC son **solo por solicitud de credenciales**, el mismo mecanismo con
condición de alcanzabilidad que usan Hosts y los contenedores descubiertos
([§ arriba](#acceso-remoto-para-contenedores-que-nspawnmgr-no-configuró-él-mismo)) — habilitado
por protocolo desde la propia página de detalle del pod una vez que el propio servicio del
invitado realmente está escuchando. Un pod nunca recibe una credencial autogenerada de la manera
en que sí lo hace el acceso SSH de un contenedor nspawn.

**Archivos** funciona vía `podman mount`, que expone el sistema de archivos overlay fusionado del
contenedor como una ruta de host ordinaria — el mismo código de navegación/subida/descarga que
usan los contenedores nspawn se ejecuta entonces directamente contra esa ruta.

**Scripts** se ejecutan vía `podman exec -i <nombre> sh -s` (stdin canalizado, un código de salida
real de vuelta a nspawnmgr). Abortar es una aproximación más estrecha que el propio kill de unidad
transitoria de nspawn: el cuerpo del script se prefija con `echo $$ > <pidfile>`, y Abortar envía
`kill -9` a ese grupo de procesos registrado — un kill de grupo de procesos real, pero no uno
realmente de todo el cgroup como lo es el aborto de nspawn, documentado en el código como un
estrechamiento conocido y deliberado, no un fallo.

**Explícitamente no ofrecido para un pod** (todo presente para contenedores nspawn): sin
credencial SSH/RDP/VNC autoaprovisionada, sin instalación de gestor de escritorio, sin mapeos de
puertos entrantes personalizados, sin conmutador de cortafuegos de salida (un pod ya tiene acceso
de red real vía netavark — no hay nada que controlar), sin montaje de ISO, sin configuración de
autoarranque/requiere al estilo `machinectl`.

**Las plantillas** viven bajo `TEMPLATES_DIR/podman/<nombre>.tar` — un archivo de `podman save`,
cargado vía `podman load` en el momento de la creación, distinto de la convención de tar plano de
nspawn. Pueble una ya sea descargando directamente de un registro
(`nspawnmgr-podman-pull-template.sh`) o convirtiendo una plantilla nspawn existente
(`nspawnmgr-podman-convert-nspawn-to-podman.sh`, y a la inversa,
`nspawnmgr-podman-convert-podman-to-nspawn.sh`, para ir en el otro sentido). Actualmente no hay
una conveniencia de "crear plantilla a partir de este pod" de la manera en que lo ofrece la propia
página de detalle de una máquina nspawn o QEMU detenida — solo descargas o conversiones nuevas.

No existe un conjunto de pruebas automatizadas dedicado para el backend podman (sin clases de
prueba `*Podman*`) — está cubierto por el conjunto de pruebas general que se ejecuta contra
simulaciones, más pruebas manuales de pila de desarrollo y recorridos en vivo en yoga. Tanto la
corrección de DNS como la decisión de red IPAM host-local de netavark de arriba están confirmadas
en vivo (vea los propios comentarios de cabecera de `nspawnmgr-configure-podman-network.sh` y
`nspawnmgr-podman-create-container.sh`) — la aproximación de aborto por kill de grupo de procesos
es la principal brecha conocida y deliberada.

### QEMU: máquinas virtuales

Junto a los contenedores nspawn y los pods podman, el menú "+" **Nuevo QEMU** crea una máquina
virtual real QEMU/KVM (insignia `QEMU`), en la misma cuadrícula de Máquinas con las mismas reglas
de propiedad/compartición. Disponible para cualquier usuario que haya iniciado sesión; el enlace
está deshabilitado mientras QEMU no esté instalado en el host (vea la página de Diagnóstico).

**Creación** (`/containers/new-qemu`): Nombre; origen del disco — **Disco vacío** (un tamaño en
GB) o **Desde plantilla** (clonar el propio disco de una Plantilla existente con backend QEMU),
mutuamente excluyentes; **Tipo de procesador**; **Número de CPU**; **Memoria (MB)**; **Tarjeta de
red** (modelo de dispositivo NIC — `virtio-net-pci` por defecto, o `e1000`/`rtl8139`/`pcnet` para
sistemas operativos invitados que necesiten uno específico, p. ej. FreeDOS típicamente necesita
`pcnet`); **Dispositivo apuntador** (`PS/2` por defecto, o `Tableta USB`, que corrige la deriva del
cursor del ratón bajo VNC para invitados con GUI — pero los invitados de la familia DOS no tienen
en absoluto una pila de controladores USB y necesitan PS/2, por eso se queda como predeterminado en
lugar de Tableta USB); y una **ISO de arranque** opcional.

`POST /api/containers/qemu` valida que exactamente uno de los campos de tamaño de disco/plantilla
esté establecido, luego `ProvisioningService.createPendingQemu()` persiste la fila y
`provisionQemu()` hace el trabajo real: clona el disco de la plantilla o crea uno vacío nuevo,
asigna un puerto VNC, escribe la unidad systemd de la VM, la inicia, genera y almacena una
contraseña VNC, y crea una conexión VNC de Guacamole a juego — dejándola en **EN EJECUCIÓN**
inmediatamente, el mismo razonamiento de lanzamiento síncrono que los pods de arriba (sin fase de
`ARRANCANDO`/sondeo de disponibilidad). Un `QemuAddressPollingService` separado y asíncrono
intenta resolver una IP del invitado después, puramente para fines de SSH — "aún no lista,
posiblemente durante mucho tiempo" es el estado normal esperado para una VM recién creada que
puede que ni siquiera tenga un sistema operativo invitado instalado en su disco todavía.

**Creación de disco** (`nspawnmgr-qemu-create-disk.sh`) es un simple `qemu-img create -f qcow2
<ruta> <tamaño>G` bajo `/var/lib/nspawnmgr/qemu-disks/`. Mismo nivel de sudo con CONTRASEÑA que
cualquier otro artefacto persistente nuevo ([§3](#3-la-cuenta-ssh-con-capacidad-sudo)) — iniciar
realmente la VM después es un paso NOPASSWD separado.

**La unidad systemd de la VM** (`nspawnmgr-qemu-write-unit.sh`) es una unidad real y persistente en
`/etc/systemd/system/nspawnmgr-qemu-<nombre>.service` — reescrita, no solo escrita una vez, tanto
en la creación como de nuevo cada vez que la ISO montada cambia mientras la VM está detenida (vea
más abajo). Es persistente en lugar de una invocación transitoria de `systemd-run` porque un
simple `systemctl start/stop` contra ella (que es como nspawnmgr siempre gestiona el ciclo de vida
de una VM QEMU) toma solo un nombre de máquina desnudo, sin nada específico de la VM a partir de lo
cual reconstruir una invocación. Su línea `ExecStart` cubre: las banderas de memoria/modelo de
CPU/número de CPU/`-enable-kvm` (KVM autodetectado vía la existencia de `/dev/kvm`); el disco qcow2
como una unidad virtio; la tarjeta de red en `nspawnbr0` con una dirección MAC derivada
deterministamente del nombre de la VM (`52:54:00:` + los primeros 3 bytes de un hash md5 del
nombre — el script de resolución de dirección tiene que derivar el mismo valor idéntico de forma
independiente, ya que ningún script lo persiste); las banderas de dispositivo apuntador (vacías
para PS/2, `-usb -device usb-tablet` para Tableta USB); el escucha VNC; un monitor QEMU de socket
Unix; y el orden de arranque (`-cdrom ... -boot order=d` cuando hay una ISO montada,
`-boot order=c` en caso contrario). Recurre a `/usr/libexec/qemu-kvm` cuando `qemu-system-x86_64`
no está en `PATH` (una peculiaridad de empaquetado de Fedora/RHEL, el mismo respaldo que ya usa
`nspawnmgr-diag-check-qemu.sh`).

**Acceso VNC**: el puerto se asigna de un rango configurable por el administrador
([`/admin/settings`](#ajustes-editables-en-vivo-adminsettings), validado para empezar en `5900` o
superior — la propia sintaxis `-vnc host:display` de QEMU dirige un número de pantalla, y
`pantalla = puerto - 5900`), eligiendo el puerto libre más bajo que ninguna otra VM ya haya
reclamado. El escucha siempre se vincula a la propia dirección de puerta de enlace de `nspawnbr0`
(`10.100.0.1`) — a diferencia de nspawn/podman, donde Guacamole marca directamente la propia
dirección interna de un contenedor, cada consola de hipervisor de VM QEMU comparte una dirección y
se diferencia puramente por puerto. Se crea automáticamente en el momento del aprovisionamiento
una conexión VNC de Guacamole con una contraseña generada — nada que el propietario tenga que
habilitar, simplemente está ahí. El propio QEMU no persiste esa contraseña a través de un
reinicio, así que `ContainerLifecycleService` vuelve a aplicar la credencial almacenada a través
del monitor HMP (vea más abajo) en cada inicio/reinicio.

**El monitor HMP** es solo interno — no hay interfaz de usuario para enviar comandos de monitor
arbitrarios. `nspawnmgr-qemu-monitor-exec.sh` transmite una línea HMP a la vez por SSH al socket
Unix del monitor de la VM vía `socat` (cerrando la conexión 2 segundos después de que QEMU deje de
responder, ya que el REPL de texto plano de HMP no tiene un encuadre limpio de respuesta por
respuesta para detectar la finalización — un punto de partida, documentado como aún no verificado
contra un monitor real de `qemu-system-x86_64`). Respalda: Detención elegante
(`system_powerdown`, una solicitud ACPI — un no-operación si aún no hay un sistema operativo
invitado instalado, por diseño, no un fallo); Pausar/Reanudar (`stop`/`cont` — el propio
equivalente de QEMU, no el congelador de cgroups que usan los contenedores nspawn); volver a
aplicar la contraseña VNC de arriba; e intercambio de ISO en vivo (`change ide1-cd0`/`eject
ide1-cd0`).

**El acceso a Archivos no está disponible para una VM QEMU** — a diferencia de `podman mount` de
podman, no hay un directorio del lado del host que navegar para una VM cuyo almacenamiento es un
único archivo de disco qcow2, y el acceso real del lado del invitado (SFTP sobre la propia
conexión SSH de la VM, una vez habilitada) aún no se ha construido. La píldora ARCHIVOS está
deshabilitada en la tarjeta de una VM QEMU por este motivo; planificado para una versión futura.

**El montaje de ISO** reutiliza la misma caché de paquetes `PackageManager.ISO` que los
contenedores nspawn ([§ arriba](#medios-extraíbles-imágenes-iso)). A diferencia del bind-mount
estático de nspawn (que solo tiene efecto en el siguiente inicio de la VM), QEMU puede
**intercambiar en vivo** el disco montado a través del monitor HMP mientras la VM está actualmente
en ejecución, y separadamente persiste la misma elección en el archivo de unidad (vía la misma
reescritura de `nspawnmgr-qemu-write-unit.sh` mencionada arriba) para que también sea correcta la
próxima vez que la VM arranque en frío.

**Plantillas**: clonar el disco de una VM a partir de una Plantilla existente con backend QEMU
(`TEMPLATES_DIR/qemu/<nombre>.qcow2`) es totalmente compatible junto a la ruta de disco
vacío-más-ISO descrita arriba — elija **Desde plantilla** en el formulario de Nuevo QEMU. La
propia página de detalle de una VM detenida también tiene un campo "Crear plantilla a partir de
esta máquina", la misma convención que usan los contenedores nspawn, para tomar una instantánea
del disco actual de una VM en una plantilla nueva e independiente.

**El ciclo de vida** tiene paridad total con nspawn/podman a través de la unidad systemd
persistente de arriba, más el monitor HMP para las operaciones que el propio QEMU tiene que pedirse
que haga con elegancia: Iniciar, Forzar detención, y Reiniciar son simples `systemctl
start/stop/restart` contra la propia unidad de la VM; la Detención elegante y Pausar/Reanudar pasan
por HMP como se describe arriba en lugar de por `systemctl freeze`/`thaw`.

**Reconciliación tras un fallo**: el mismo `ContainerLivenessPollingService` descrito arriba para
podman también cubre QEMU — la propia unidad de cada VM EN EJECUCIÓN se vuelve a comprobar
(`systemctl is-active`) según la misma programación de ~30s, y el propio estado de nspawnmgr
cambia a DETENIDO en el momento en que la unidad misma se ha detenido o ha desaparecido sin que
nadie se diera cuenta. **Sigue siendo una limitación real, no completamente resuelta**: esto solo
detecta que la propia unidad/proceso desaparece, no un fallo exclusivo del sistema operativo
invitado en el que el proceso permanece vivo pero lo que se ejecuta dentro se ha colgado o muerto —
`systemctl is-active` no tiene ninguna visibilidad sobre eso, y ningún backend ofrece una forma de
preguntar. Vale la pena tenerlo presente si la insignia de una VM alguna vez parece no coincidir
con la realidad a pesar de que el proceso siga técnicamente en ejecución.

Tampoco existe un conjunto de pruebas automatizadas dedicado para el backend QEMU (sin clases de
prueba `*Qemu*`) — cubierto por el conjunto general contra simulaciones, más pruebas manuales de
pila de desarrollo y recorridos en vivo; el ajuste de dispositivo apuntador específicamente se ha
confirmado en vivo contra una VM real de KolibriOS en yoga. La heurística de encuadre de respuesta
del monitor HMP de arriba, y algunas de las propias comprobaciones de
`nspawnmgr-diag-check-qemu.sh`, están explícitamente marcadas como no verificadas contra un
monitor real de `qemu-system-x86_64` en sus propios comentarios de cabecera.

**Descubrir máquinas** ([§ arriba](#descubrir-máquinas-creadas-fuera-de-nspawnmgr)) cubre los tres
backends en un solo clic — ejecuta un paso separado sobre `machinectl`, `podman`, y las propias
unidades systemd de QEMU cada uno, registrando cualquier cosa no rastreada que encuentre en
cualquiera de ellos, saltándose por completo un backend si no está instalado en el host en
absoluto.

### Instalación de paquetes: primero se descargan, no se instalan directamente desde una descarga en vivo por red

Se ha confirmado que un gestor de paquetes ejecutado *desde dentro* de un contenedor en ejecución
no es fiable resolviendo sus propios espejos, incluso cuando la propia red/DNS del host funciona
bien. SSH, RDP, VNC, y el paquete del gestor de escritorio reciben todos el mismo tratamiento:
nspawnmgr los descarga (con toda su cadena de dependencias, solo descarga — nada se instala
todavía) antes de ejecutar la instalación real *dentro* del contenedor. Aplica a plantillas
**APT, DNF y PACMAN** usando los comandos de instalación por defecto (sin anular) — una anulación
de comando de instalación personalizada no se puede analizar de forma segura para extraer nombres
de paquete que predescargar, y recurre a la instalación de hoy solo dentro del contenedor (que
necesita que la propia red/DNS del contenedor realmente funcione). **APK** está excluido por
completo: su propia instalación local ya resuelve dependencias desde repositorios configurados por
sí sola, sin necesidad de predescarga (discutible de todos modos — los contenedores basados en
Alpine no funcionan del todo en esta aplicación hoy, vea más abajo).

**El propio paso de descarga de APT se ejecuta del lado del host** — un proceso apuntado
directamente al propio directorio rootfs del contenedor (`apt-get -o Dir=<rootfs>`), usando la
propia red funcional del host — ya que `apt-get` siempre está en el propio `PATH` de este host
(el `.deb` de este proyecto solo apunta a Debian/Ubuntu). **DNF y PACMAN no pueden hacer eso**:
ninguno de los dos está nunca en el propio `PATH` de este host en absoluto, así que su propio paso
de descarga en su lugar se ejecuta *dentro del propio contenedor*, vía `systemd-run --machine=`
(la misma primitiva de ejecución no interactiva dentro del contenedor que ya usa el paso de
instalación real) — solo descarga, igual que APT, así que sigue sin tocar el estado de paquetes
instalados de dpkg/rpm/pacman. Una consecuencia: DNF/PACMAN no obtienen la propia reutilización de
APT de "un paquete ya en caché y aún válido nunca se vuelve a descargar" entre contenedores (eso
depende de un directorio de caché plano del lado del host que dnf/pacman ejecutándose *dentro* del
propio espacio de nombres de montaje de un contenedor no puede ver) — cada predescarga de
DNF/PACMAN vuelve a descargar todo desde cero. Los tres siguen almacenando en caché la cadena de
dependencias bajo `/var/cache/nspawnmgr/packages/<manager>/auto/` para la visibilidad de la página
de Paquetes del administrador, sin importar dónde se ejecutara la propia descarga.

Una excepción: GNOME/KDE en DNF se instalan vía un *grupo* comps (`dnf group install`), no un
paquete plano con nombre — `dnf --downloadonly` (lo que usa la predescarga) no tiene equivalente
para resolver/almacenar en caché la pertenencia de un grupo entero de antemano, solo paquetes
individuales, así que esas dos combinaciones deliberadamente se saltan la predescarga y caen
directamente a la instalación de grupo dentro del contenedor (necesitando la propia red/DNS del
contenedor, igual que lo haría un comando anulado). Xfce no tiene este problema — confirmado en
vivo, Fedora lo distribuye como un paquete plano con nombre (`xfce4`), no como un grupo comps en
absoluto.

Ese paso real de instalación dentro del contenedor nunca vuelve a ejecutar la propia actualización
de metadatos de `apt-get update`/`dnf`: es redundante, ya que el paso de predescarga ya refrescó
el índice (del lado del host para APT, dentro del contenedor para DNF/PACMAN) momentos antes, así
que lo que lee el paso de instalación ya está fresco, y cada paquete que necesita ya está sentado
en la propia caché local del contenedor — cada script de predescarga deja allí una copia
precisamente por este motivo.

El paquete de nivel superior en sí (no sus dependencias transitivas, que siguen siendo un detalle
de implementación del directorio de caché) también se registra en la caché de administración de
**Paquetes** descrita justo debajo, así que lo que nspawnmgr obtuvo para su propio aprovisionamiento
es visible y reutilizable allí también, no solo un efecto secundario oculto de la creación de un
contenedor.

### Subir e instalar paquetes arbitrarios

Los administradores también pueden subir cualquier archivo de paquete directamente: **Paquetes**
(desde la lista de contenedores, solo administrador) acepta un archivo `.deb`/`.rpm`/lo que sea que
use su gestor de paquetes más una descripción opcional. Cada propietario de contenedor entonces ve
una sección **Instalar paquete** correspondiente en la propia página de detalle de su contenedor
(solo se ofrecen los paquetes para el propio gestor de paquetes de ese contenedor) — elegir uno y
hacer clic en Instalar lo copia al contenedor, luego, para paquetes **APT, DNF y PACMAN**, primero
*simula* la instalación (`apt-get install -s` / `dnf install --assumeno` / `pacman -U --print`, sin
hacer cambios) contra el propio estado del contenedor para encontrar cualquier dependencia que aún
no tenga. Cualquier cosa que falte se obtiene de la misma manera que ya lo hace el aprovisionamiento
de SSH/RDP/VNC/gestor de escritorio (vea arriba — del lado del host para APT, dentro del propio
contenedor vía `systemd-run --machine=` para DNF/PACMAN, ya que ninguno de los dos está nunca en el
propio `PATH` de este host) y se registra aquí también en la caché de paquetes, luego la
instalación real se ejecuta mediante el propio comando de instalación de archivo local del gestor
de paquetes (`apt-get install <ruta>` / `dnf install <ruta>` / `pacman -U --noconfirm <ruta>`) —
su propia resolución de dependencias recoge tanto el archivo subido como lo que se acaba de
predescargar en un único paso coherente. La propia instalación local de DNF/PACMAN normalmente
resolvería las dependencias directamente desde el acceso de red del contenedor, igual que hace
cualquiera de los dos para cualquier paquete con nombre — el paso de predescarga se ejecuta de
todos modos, deliberadamente, por consistencia con la propia postura de APT de "nunca dejar que un
contenedor alcance la red directamente para una búsqueda en vivo de espejo del gestor de paquetes"
(la propia predescarga de DNF/PACMAN de todos modos sigue necesitando la red del contenedor para la
propia descarga dentro del contenedor — simplemente mantiene esa necesidad contenida a un único
paso de solo descarga, no interactivo, en lugar del comando de instalación real). Este subpaso
necesita el mismo nivel de contraseña de sudo que la creación de contenedores, así que falla
directamente (sin instalación parcial silenciosa) si no hay ningún secreto de sudo almacenado
configurado y la solicitud no proporcionó uno. **El soporte de DNF y PACMAN para instalar un
paquete subido *dentro de un contenedor Fedora/Arch* no está verificado** — distinto de instalar
*el propio nspawnmgr* en un host RPM/Arch real, que sí está verificado (vea las secciones de
instalación de paquetes RPM y Arch de arriba); este flujo específico de subida de paquete dentro
del contenedor nunca se ha ejercitado contra un contenedor Fedora/Arch real, solo construido según
el contrato de CLI documentado de cada herramienta con todo el cuidado posible — informe cualquier
discrepancia en vivo que encuentre. **PACMAN es el más especulativo de los dos**: a diferencia de
`apt-get install -s`/`dnf install --assumeno`, que son los propios modos de simulación bien
documentados de apt/dnf, el comportamiento de `pacman -U --print` para una simulación completa de
cadena de dependencias de archivo local nunca se ha ejercitado en ningún sitio de este proyecto, ni
siquiera manualmente. Los paquetes **APK** se saltan todo esto y simplemente ejecutan una única
instalación local (`apk add <ruta>`) sin resolución de dependencias — una dependencia faltante ahí
sigue siendo un error visible en la salida, no se corrige automáticamente (la propia instalación
local de APK en realidad sí resolvería dependencias desde repositorios configurados, pero los
contenedores basados en Alpine no funcionan del todo en esta aplicación hoy de todos modos — vea
más abajo). Los paquetes que nspawnmgr descargó automáticamente (ya sea para su propio
aprovisionamiento de SSH/RDP/VNC/gestor de escritorio, o como una dependencia obtenida por este
flujo) también aparecen aquí, atribuidos a cualquiera que sea el contenedor cuya creación o
instalación los obtuvo primero, junto a cualquier cosa que un administrador haya subido a mano.

El botón **"Mostrar dependencias transitivas"** de la página de Paquetes llena el hueco que esto
deja deliberadamente: elija un gestor de paquetes (APT/DNF/PACMAN, los mismos tres con un
directorio de caché de predescarga en absoluto) y lista cada archivo que realmente está sentado en
el propio directorio compartido `/var/cache/nspawnmgr/packages/<manager>/auto` de ese gestor, con
el tamaño en bytes. Esto se genera fresco ejecutando un comando externo y leyendo el directorio
real cada vez que se hace clic en el botón (`nspawnmgr-list-auto-cache.sh`, un script envoltorio
de solo lectura y NOPASSWD) — nada de esto está almacenado en la base de datos, a diferencia de
los paquetes de nivel superior de la tabla de arriba. Útil para confirmar que una dependencia
realmente llegó al disco, o para calcular a ojo cuánto ha acumulado con el tiempo un directorio de
caché compartido dado por un gestor de paquetes determinado.

### Medios extraíbles (imágenes ISO)

**ISO** es un valor real de `PackageManager`, no una caché/entidad/página de administración
separada — súbala desde la misma página de administración de **Paquetes** igual que un
`.deb`/`.rpm`, eligiendo `ISO` en lugar de `APT`/`DNF`/`APK`/`PACMAN`. La maquinaria de
instalación al estilo `.deb`/`.rpm` no se aplica a esto (no hay comando de instalación para
`ISO`, y `Template.packageManager` nunca puede ser `ISO` — el propio desplegable del formulario de
administración de Plantillas lo excluye), pero la ruta de subida/caché/publicación por CI es
idéntica de todos modos, por elección deliberada frente a construir una segunda ruta paralela.
Cualquier propietario de contenedor puede entonces configurar una ISO subida desde la sección
"Medios extraíbles" de la propia página de detalle de su contenedor — como máximo una por
contenedor a la vez, como una unidad de CD real, siempre montada de solo lectura en la ruta fija
`/mnt/cdrom`. Montar una ISO diferente mientras ya hay una configurada expulsa automáticamente la
antigua primero; no hay un paso separado de expulsar-y-luego-montar.

**Un ajuste persistente y declarativo — exactamente como los [mapeos de puertos
personalizados](#mapeos-de-puertos-personalizados-y-acceso-saliente), no una operación en vivo.**
Montar/expulsar reescribe inmediatamente el archivo `.nspawn` del contenedor (una línea estática
`[Files]` `BindReadOnly=`), pero solo tiene efecto la próxima vez que el contenedor se
(re)inicia, y permanece configurado a través de los reinicios hasta que se cambie o expulse
explícitamente — *no* requiere que el contenedor esté en ejecución para configurarse, y una
detención/reinicio *no* lo borra. La mitad del lado del host (un archivo ISO montado con loop en
una ruta fija por contenedor, `nspawnmgr-mount-iso.sh`/`nspawnmgr-unmount-iso.sh`) se configura/
desmonta en cuanto usted monta/expulsa, independientemente de si el contenedor está en ejecución
en ese momento; un reinicio del host, sin embargo, actualmente no vuelve a establecer ese montaje
en loop por sí solo, así que un contenedor arrancado después de un reinicio del host con una ISO
todavía configurada fallará al iniciar hasta que esto se solucione a mano
(`mount -o loop,ro <iso> /var/lib/nspawnmgr/iso-mounts/<nombre>`) — una limitación conocida, no
reconciliada automáticamente hoy.

**Esto hace de `systemd-networkd` un requisito previo estricto, no solo una comodidad para el
acceso saliente** — el propio postinst de nspawnmgr lo usa para crear y configurar `nspawnbr0` en
sí (vea arriba), y tanto la comprobación de disponibilidad de nspawnmgr como `guacd` marcan
directamente la dirección `host0` de un contenedor en cuanto la tiene, así que un contenedor que
nunca obtiene una (`host0` nunca habilitado dentro de la plantilla — vea el paso 2 más abajo) nunca
sale de ARRANCANDO, punto final, no solo lentamente. Audite cualquiera de sus propias plantillas en
busca de `systemctl enable systemd-networkd` si los contenedores dejan de alcanzar EN EJECUCIÓN.

El único reenvío entrante que queda a nivel de host son los [mapeos de puertos
personalizados](#mapeos-de-puertos-personalizados-y-acceso-saliente) — totalmente opcionales,
gestionados por el propietario, y usando el mismo mecanismo `.nspawn` de
`Port=tcp:<puerto-host>:<puerto-contenedor>` (que `systemd-nspawn` de todos modos sigue
configurando automáticamente como reglas DNAT al iniciar).

Concretamente, para terminar de configurar esto:

1. `sudo systemctl enable --now systemd-networkd` (**Diagnóstico de red** tiene una comprobación +
   una corrección de un clic para esto), y `sudo sysctl -w net.ipv4.ip_forward=1` (persístalo bajo
   `/etc/sysctl.d/`) — `IPMasquerade=yes` en el propio archivo `.network` de `nspawnbr0` (vea
   arriba) añade la regla NAT, pero el reenvío real de paquetes entre interfaces es un ajuste
   separado, de todo el kernel, que este paquete no activa por usted. Si NetworkManager/ifupdown
   ya gestiona su NIC principal, dígale que deje `nspawnbr0` en paz (p. ej.
   `unmanaged-devices=interface-name:nspawnbr0` de NetworkManager.conf) para que networkd quede
   libre de gestionarlo.
2. Dentro de la **plantilla** del contenedor, antes de hornear (el mismo paso que el horneado de
   `openssh-server` en [§2](#2-requisitos-previos-del-host)): `systemctl enable systemd-networkd`
   para que `host0` realmente recoja su configuración DHCP desde el puente — la salida de
   `debootstrap` no lo habilita por defecto. **Obligatorio**, no opcional: sáltese esto y los
   contenedores de esa plantilla nunca salen de ARRANCANDO.
3. Inicie (o reinicie) un contenedor — `machinectl start` esclaviza su veth en `nspawnbr0`,
   obtiene una dirección y ruta vía DHCP desde el puente, y nspawnmgr/`guacd` ya pueden alcanzarlo
   directamente.

### Resolución de contenedores por nombre

Los contenedores gestionados ya pueden alcanzarse entre sí por IP (nada en la propia configuración
de cortafuegos de nspawnmgr bloquea el tráfico `FORWARD` de contenedor a contenedor — la regla
DROP de la cadena `NSPAWNMGR-OUTBOUND` solo coincide con los paquetes salientes *propios* de un
contenedor, sin importar el destino). Lo que falta sin esta sección es una forma de buscar un par
por nombre en lugar de por su dirección interna, que se asigna por DHCP por contenedor y puede
cambiar entre reinicios.

`dnsmasq` es una dependencia real de `apt` de este paquete (a diferencia de guacd/Tomcat, que
vienen incluidos — vea [§2](#2-requisitos-previos-del-host); el comportamiento de servir el
archivo de hosts de `dnsmasq` es lo bastante simple y estable entre versiones como para que no
haya necesidad de fijar una). Instalado y configurado automáticamente: vinculado solo a
`nspawnbr0` (nunca alcanzable desde la propia interfaz de LAN/salida del host — no lo es, y nunca
debe llegar a ser, un resolver abierto), sirviendo lo que sea que haya en
`/etc/nspawnmgr/dns-hosts`. Cada contenedor también recibe automáticamente la propia dirección de
`nspawnbr0` (`10.100.0.1`) como su servidor DNS, directamente del propio archivo `.network` de
`nspawnbr0` — sin necesitar ningún paso adicional del administrador. nspawnmgr regenera
`/etc/nspawnmgr/dns-hosts` (`ContainerDnsSyncService`, cada ~15s) a partir del propio nombre y
dirección interna de cada contenedor GESTIONADO actualmente EN EJECUCIÓN — la misma dirección que
ya resuelven guacd/la comprobación de disponibilidad (vea arriba), así que no hay nada nuevo que
descubrir. dnsmasq no nota por sí solo un archivo `addn-hosts` cambiado (sin recarga automática/
basada en inotify para ello, solo SIGHUP o un reinicio), así que cada escritura va seguida de una
recarga (`nspawnmgr-reload-dnsmasq.sh`/`DnsReloader`) — sin ella, los contenedores seguirían
fallando al resolverse entre sí sin importar lo actualizado que esté realmente el archivo en disco.

Dado que esta instancia de `dnsmasq` se ejecuta directamente en el host, también lee y sirve a los
contenedores el propio `/etc/hosts` del host por defecto (confirmado en vivo como el comportamiento
deseado) — las propias entradas estáticas de LAN de un administrador ahí (p. ej.
`192.168.1.15 acer`) se vuelven resolubles desde dentro de cada contenedor también, no solo desde
el propio host. La única salvedad: si `/etc/hosts` también asigna el propio nombre de host desnudo
del host a una dirección de loopback (la propia convención de Debian `127.0.1.1 <nombre-host>`) *y*
ese mismo nombre desnudo se establece como el ajuste de nombre de host externo de abajo, las dos
fuentes chocan y dnsmasq puede responder con cualquiera de las dos direcciones — evite elegir un
nombre corto ya asignado en `/etc/hosts` para ese ajuste.

`/etc/nspawnmgr/dns-hosts` también lleva una entrada más, fija: el propio nombre de host externo
del host (`nspawnmgr.host.external-hostname`/`HOST_EXTERNAL_HOSTNAME` — detectado
automáticamente en el momento de la instalación por `setup-sudo-account.sh`, editable en vivo
después en [`/admin/settings`](#ajustes-editables-en-vivo-adminsettings)), apuntando a la propia
dirección fija de `nspawnbr0` (`10.100.0.1`). Un contenedor no tiene ninguna otra ruta de vuelta al
host en absoluto — esto es lo que permite resolver el propio nombre del host para alcanzar
cualquier cosa que el host reenvíe de vuelta (p. ej. un [mapeo de puerto
personalizado](#mapeos-de-puertos-personalizados-y-acceso-saliente)). Se mantiene sincronizado de
la misma manera y según la misma programación que las entradas de contenedor de arriba; se omite
por completo mientras todavía está en su valor predeterminado sin configurar `localhost` (asignar
"localhost" en sí a `10.100.0.1` sería activamente incorrecto, no solo poco útil).

Esta misma instancia de `dnsmasq` es también el *único* servidor DNS de cada contenedor — no solo
para nombres `.internal` — así que también reenvía cualquier cosa fuera de `.internal` a los
resolvedores de nivel superior configurados, `nspawnmgr.dns.upstream-servers` (por defecto
`1.1.1.1,9.9.9.9`), editable en vivo en
[`/admin/settings`](#ajustes-editables-en-vivo-adminsettings) — p. ej. para apuntar los
contenedores a un servidor DNS corporativo en su lugar. Sin ningún resolvedor de nivel superior
configurado, el propio `dnf`/`pacman`/`apt` de un contenedor (obteniendo de sus propios espejos de
paquetes reales) o cualquier otra cosa que necesite un nombre de host real de internet falla
directamente con "Could not resolve host" — confirmado en vivo. Sigue sin ser un resolver abierto
en el sentido de arriba: el reenvío ocurre a través de la propia ruta normal de internet del host,
y `dnsmasq` en sí sigue vinculado solo a `nspawnbr0`, inalcanzable desde fuera del puente de
contenedores.

Los servidores de nivel superior viven en su propio archivo,
`/etc/dnsmasq.d/nspawnmgr-upstream.conf` — separado del `nspawnmgr.conf` principal de arriba —
incluido automáticamente junto a él por el propio `conf-dir=/etc/dnsmasq.d/` de dnsmasq
(predeterminado de Debian en `/etc/dnsmasq.conf`), sin necesitar ninguna directiva adicional.
`ContainerDnsSyncService` lo mantiene sincronizado con el ajuste actual de la misma manera que
mantiene `dns-hosts` sincronizado con los contenedores en ejecución (sondeado cada ~15s, solo
reescrito cuando el valor efectivo realmente cambia). `postinst` lo siembra con el mismo valor
predeterminado `1.1.1.1`/`9.9.9.9` en la primera instalación (solo si el archivo aún no existe),
así que la resolución de nivel superior funciona desde el primer arranque, antes de que el propio
nspawnmgr siquiera esté activo para hacerse cargo de sincronizarlo.

Los contenedores se resuelven entre sí por su nombre desnudo de nspawnmgr (`b1`) o por un FQDN bajo
el sufijo fijo `.internal` (`b1.internal`) — las opciones `domain=`/`expand-hosts` de dnsmasq
sirven ambas formas a partir de las mismas entradas de `dns-hosts` automáticamente, sin
configuración separada. `internal` es el TLD de uso especial de la IANA reservado exactamente para
esto (RFC 8375, la misma categoría que `home.arpa`), no un dominio inventado, así que está
garantizado que nunca colisionará con uno público real. El alcance es solo contenedores GESTIONADOS
(los hosts EXTERNOS, configurados por el administrador, ya tienen su propio `hostname` y no se
añaden aquí), y el espacio de nombres es plano a través de todos ellos — esto es puramente
alcanzabilidad a nivel de red, independiente de qué contenedores puede ver o conectar un usuario
dado en la interfaz web (la cuadrícula de Máquinas solo muestra máquinas que un usuario posee o con
las que se ha compartido, excepto para un administrador, que ve todo sin importar la propiedad).

Se necesitan dos piezas más para que esto funcione de extremo a extremo:

- **El lado del contenedor**: `systemd-resolved` se niega a enviar un nombre no cualificado (sin
  punto) como `b2` a un servidor DNS real en absoluto — solo a LLMNR/mDNS — a menos que el enlace
  tenga configurado un dominio de enrutamiento/búsqueda con el que cualificarlo. DHCP podría
  suministrar esto, pero eso necesita que el propio `80-container-host0.network` del contenedor
  (generado por el propio `systemd-nspawn`, no algo que controle esta plantilla) se sume con
  `UseDomains=yes`, lo cual no hace por defecto. La plantilla en su lugar distribuye un fragmento
  estático en `/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf`
  (`[Network]\nDomains=internal`), fusionado por nombre de archivo de la misma manera que lo es un
  fragmento de unidad systemd — evita DHCP por completo y no depende de que se envíe ninguna opción
  en absoluto.
- **El lado de dnsmasq**: `domain=`/`expand-hosts` por sí solos solo controlan el sufijo con el que
  dnsmasq *decora sus propias respuestas* — no lo hacen autoritativo para una consulta que ya
  *llega* precualificada (exactamente lo que ahora envía un contenedor con el dominio de
  enrutamiento de arriba). Sin también establecer `local=/internal/`, una consulta entrante de
  `b2.internal` cae por completo a través de la coincidencia de hosts/`addn-hosts` y se reenvía a
  un nivel superior como cualquier otro nombre — `.internal` no existe públicamente, así que eso
  simplemente falla (y de otro modo filtraría nombres de contenedor a cualquier resolvedor público
  que esté configurado). `local=/internal/` marca `.internal` como la propia zona autoritativa de
  dnsmasq: responder solo a partir de sus propios datos de hosts, `NXDOMAIN` para cualquier cosa
  genuinamente desconocida ahí, nunca reenviar.

Si alguna vez edita a mano cualquiera de los dos archivos de dnsmasq directamente en un host en
ejecución: `domain=`, `expand-hosts`, `local=` (en `nspawnmgr.conf`), y `server=` (en
`nspawnmgr-upstream.conf`) son todos estructurales — dnsmasq solo los analiza al arrancar el
proceso, confirmado en vivo — a diferencia de `addn-hosts`, que
`DnsReloader.reload()`/`nspawnmgr-reload-dnsmasq.sh` recarga en caliente correctamente vía
`SIGHUP`. Un simple `systemctl reload dnsmasq` después de editar a mano cualquiera de los
estructurales no tiene efecto; use `systemctl restart dnsmasq`. `ContainerDnsSyncService` ya
conoce esta distinción: un cambio de `addn-hosts` pasa por `DnsReloader.reload()` (SIGHUP) como
arriba, pero un cambio de servidores de nivel superior pasa por el separado
`DnsReloader.restart()`/`nspawnmgr-restart-dnsmasq.sh` (un `systemctl restart` completo) en su
lugar — usar `reload()` para ese dejaría el archivo en disco correcto mientras dnsmasq sigue
respondiendo silenciosamente con lo que sea que arrancó por última vez de verdad. Una instalación/
actualización normal del paquete no necesita ninguna de las dos: el postinst del `.deb` siempre
emite su propio `restart` completo cuando (re)instala `nspawnmgr.conf`.

### Descubrir máquinas creadas fuera de nspawnmgr

Si una máquina se creó a mano directamente en el host — `machinectl clone`/`debootstrap`/
`import-tar` ejecutado por usted mismo, o una imagen restaurada de una copia de seguridad —
nspawnmgr no tiene ni idea de que existe hasta que un administrador hace clic en **Descubrir
máquinas** en la lista de contenedores. Eso compara cada nombre de imagen que `machinectl`
actualmente conoce contra la propia base de datos de nspawnmgr y registra lo que aún no esté
rastreado como un contenedor GESTIONADO ordinario, **propiedad de cualquiera que sea el
administrador que ejecutó el descubrimiento**. Volver a ejecutarlo es seguro — cualquier cosa ya
rastreada (por nombre) se salta.

El descubrimiento registra la existencia de la máquina y le permite iniciarla/detenerla/eliminarla
y verla resuelta por nombre (vea arriba). Deliberadamente nunca instala una cuenta de administrador
SSH/RDP/VNC de la manera en que sí lo hace crear un contenedor a través de nspawnmgr — a diferencia
de un contenedor que el propio nspawnmgr aprovisionó, no hay forma de saber qué ya existe dentro de
una imagen construida a mano, así que nunca asume un nombre de cuenta ni ejecuta `useradd`/instala
un servidor para ninguno de los tres. Lo que sí hace: justo después de registrar cada máquina,
comprueba si SSH (puerto 22), RDP (puerto 3389), o VNC (puerto 5900) ya está escuchando, y si es
así, conecta una conexión de Guacamole automáticamente para ella — en modo
**solicitud-de-credenciales**, el mismo mecanismo que usa la página de Hosts de abajo, así que se
le pide un nombre de usuario/contraseña cada vez que se conecta en lugar de que nspawnmgr genere y
almacene uno. Si ninguno de esos puertos estaba abierto todavía en el momento del descubrimiento
(o habilita uno en la máquina después), hágalo manualmente desde la propia página de detalle del
contenedor en su lugar — vea "Acceso remoto" más abajo.

### Acceso remoto para contenedores que nspawnmgr no configuró él mismo

La página de detalle de un contenedor tiene una sección **Acceso remoto** para cada uno de SSH,
RDP y VNC siempre que nspawnmgr no tenga ninguna credencial generada para ese protocolo en él —
siempre cierto para un contenedor descubierto, y también cierto para un contenedor ordinario
creado por nspawnmgr si se rechazó RDP/VNC cuando se creó. Hacer clic en **Habilitar acceso
SSH/RDP/VNC** comprueba que el puerto realmente está escuchando en este momento y, solo si es así,
conecta una conexión de Guacamole de solicitud-de-credenciales exactamente igual que el propio paso
de conexión automática del descubrimiento de arriba; **Deshabilitar** la elimina de nuevo. Esta
comprobación ocurre una vez, en el momento en que hace clic en Habilitar — si el servicio dentro
del contenedor se detiene de nuevo después, el botón Conectar permanece habilitado hasta el
siguiente intento de conexión fallido, en lugar de que nspawnmgr vuelva a sondear continuamente
cada contenedor en segundo plano.

Esta sección deliberadamente nunca se ofrece para un protocolo que nspawnmgr ya gestiona con una
credencial generada real (el SSH de cada contenedor, y RDP cuando se solicitó en la creación) —
esa conexión se deja completamente en paz, así que esta función nunca puede reemplazar
silenciosamente credenciales generadas que funcionan con una conexión de solicitud-de-credenciales.

### Hosts: máquinas externas gestionadas por el administrador

Un **Host** es una entrada para una máquina arbitraria en la red que no es en absoluto un
contenedor gestionado por nspawnmgr — una máquina Windows existente, un NAS, el servidor de otro
equipo, cualquier cosa alcanzable por SSH/RDP/VNC que sea conveniente acceder a través del mismo
flujo de inicio de sesión único de Guacamole que todo lo demás aquí. No hay una página separada de
Hosts: un Host es una fila de `Container` por debajo (tipo `EXTERNAL`), así que aparece como una
tarjeta ordinaria — una insignia fija `HOST` en lugar de una insignia de backend — justo al lado de
las máquinas nspawn/podman/QEMU en la cuadrícula principal de **Máquinas**, y su página de detalle
es la misma ruta `/containers/{id}` que usa cualquier otra máquina. Los administradores añaden uno
desde el elemento **Nuevo Host** del menú "+" (`/admin/hosts/new`, solo administrador): un nombre,
un nombre de host/IP, un nombre de usuario propietario (debe pertenecer a un usuario que ya haya
iniciado sesión al menos una vez), y cuáles de SSH/RDP/VNC ofrecer más el puerto para cada uno. Un
administrador que vea la propia página de detalle de ese host obtiene los botones **Editar host**
(de vuelta al mismo formulario, en `/admin/hosts/{id}/edit`) y **Eliminar host** en su panel
Gestionar — no hay una página separada de lista de hosts; la base de datos es la única fuente de
verdad.

**La visibilidad sigue la misma regla de propietario/administrador/compartido que cualquier otra
máquina** — un Host no es público solo porque lo haya creado un administrador; solo un
administrador, su propietario, o alguien con quien se haya compartido explícitamente lo ve en su
propia cuadrícula de Máquinas (`ContainerRepository.findVisibleToUserOrderByName` aplica esto de
manera uniforme a través de las filas de nspawn, podman, QEMU y Host por igual).

**EN EJECUCIÓN/DETENIDO se resuelve en vivo, no se almacena.** Dado que nspawnmgr no controla en
absoluto el ciclo de vida de un Host, la insignia de su estado proviene de una única comprobación
de alcanzabilidad TCP (`HostLivenessService`) contra cualquiera de sus puertos SSH/RDP/VNC
configurados que esté habilitado — SSH primero si está presente, luego RDP, luego VNC — con caché
de un minuto por host para que la cuadrícula de Máquinas y la propia página de detalle del host no
disparen cada una un sondeo fresco en cada solicitud. Un Host sin ninguno de los tres habilitado no
tiene nada que sondear y siempre muestra EN EJECUCIÓN.

Las conexiones siempre piden credenciales en vivo — nspawnmgr nunca almacena una contraseña para
un host, el mismo mecanismo de solicitud-de-credenciales que usan tanto la propia conexión
automática del descubrimiento como la sección de Acceso remoto por contenedor de arriba.

El campo de nombre de host/IP puede ser un nombre de host real, no solo una dirección — en una
instalación autoalojada, el propio cliente SSH/RDP/VNC de Guacamole se ejecuta dentro del propio
contenedor autoalojado `nspawnmgr`, cuya única ruta DNS es el propio dnsmasq de nspawnmgr (nombres
de contenedor más resolvedores públicos de nivel superior), sin ninguna visibilidad hacia la propia
resolución de nombres de una LAN privada. Para sortear eso, nspawnmgr vuelve a resolver él mismo el
nombre de host en el host subyacente (vía la misma cuenta SSH con capacidad sudo usada para toda
otra operación privilegiada) cada vez que alguien se conecta, y le entrega a Guacamole la dirección
resuelta directamente en lugar del nombre de host — así que un nombre solo-de-LAN que solo conoce
el propio DNS/NetBIOS/mDNS de su red sigue funcionando, y una dirección reasignada por DHCP se
recoge automáticamente en la siguiente conexión sin necesitar que un administrador se dé cuenta y
vuelva a guardar la entrada. Si el nombre de host no se resuelve en el host en el momento de la
conexión, el intento de conexión falla con un error claro en lugar de proceder con una dirección
obsoleta.

La compartición funciona de la misma manera que para los contenedores: el propietario gestiona
quién más puede conectarse desde la propia página de detalle de la entrada. Un administrador que
no es el propietario ve un botón **Tomar posesión** bajo Gestionar ahí en su lugar — útil para
hacerse cargo de un Host (o cualquier máquina) cuyo propietario se haya marchado desde entonces,
sin necesitar acceso a la base de datos.

Los botones SSH/RDP/VNC tanto en la cuadrícula de Máquinas como en la propia página de detalle de
un Host abren la sesión de Guacamole en una pestaña nueva del navegador en lugar de navegar fuera
de la página — útil cuando se conecta a varias máquinas desde la misma página. Abrir uno desde la
tarjeta de un Host usa `/hosts/{name}/session/{protocol}`, su propio espacio de nombres de URL
distinto del `/containers/{name}/session/{protocol}` de una máquina ordinaria — un Host es una fila
de Container por debajo como se señaló arriba, pero la URL de *sesión* que un usuario realmente ve
en su navegador deliberadamente no dice "containers" para algo que no lo es desde el punto de vista
de un administrador. Ambas rutas renderizan la plantilla/JS idéntica por debajo (un iframe más una
petición al mismo endpoint de API `/api/containers/{id}/session/{protocol}`); solo difiere la URL
de la página. Ambas se basan en el **nombre** de la máquina, no en su id numérico — una elección
deliberada para que la URL en un enlace compartido o en el historial de un navegador siga siendo
significativa.

### Mapeos de puertos personalizados y acceso saliente

Más allá de SSH/RDP de arriba, el **propietario** de un contenedor puede autoservirse dos cosas más
desde su página de detalle — sin necesitar ninguna acción del administrador para ninguna de las
dos:

- **Mapeos de puertos entrantes personalizados**: cualquier reenvío adicional de
  puerto-host-TCP-o-UDP → puerto-contenedor, con el propietario eligiendo ambos números de puerto
  exactamente. nspawnmgr comprueba que el puerto de host solicitado no esté ya vinculado por otro
  mapeo personalizado antes de aceptarlo. Un mapeo se escribe en el archivo `.nspawn`
  inmediatamente pero solo tiene efecto la próxima vez que el contenedor se (re)inicia — añadir
  uno a un contenedor en ejecución muestra un aviso de "se requiere reinicio" en lugar de
  reiniciarlo automáticamente.
- **Conmutador de acceso a internet saliente**: a diferencia de la configuración de enmascaramiento
  de todo el host, todo-o-nada, de arriba, cada contenedor puede tener individualmente su acceso
  saliente bloqueado. nspawnmgr gestiona esto por sí mismo con una cadena iptables dedicada
  `NSPAWNMGR-OUTBOUND` (creada automáticamente la primera vez que se necesita, saltada desde la
  parte superior de `FORWARD`) que contiene una regla `DROP` por contenedor con salida
  deshabilitada, con clave en la propia interfaz veth real del lado del host de ese contenedor —
  que nspawnmgr busca dinámicamente cada vez (vía el ifindex par del veth), ya que, como arriba,
  el nombre del veth no es una cadena predecible derivada del nombre del contenedor. Activar esto
  tiene efecto inmediato, sin necesitar reinicio, para un contenedor en ejecución.
- **Lista de permitidos de salida**: mientras el acceso saliente está deshabilitado, el
  propietario todavía puede abrir un hueco hacia destinos específicos — una dirección IPv4
  literal, puerto, y protocolo (TCP/UDP) — p. ej. `127.0.0.1` para que el contenedor pueda
  alcanzar otro contenedor/servicio coubicado sin concederle acceso general a internet.
  Implementado como reglas ACCEPT antes de la regla DROP del contenedor en la misma cadena
  `NSPAWNMGR-OUTBOUND`; cada cambio vacía y reconstruye las reglas de ese contenedor desde cero en
  lugar de parchearlas en su sitio. No tiene efecto mientras el acceso saliente está habilitado —
  todo ya es alcanzable en ese caso. También tiene efecto inmediato, sin necesitar reinicio.

Ambos requieren que el comando `iptables` esté disponible y usable sin contraseña vía la cuenta con
capacidad sudo de [§3](#3-la-cuenta-ssh-con-capacidad-sudo) — la misma cuenta y mecanismo que
nspawnmgr ya usa para escribir archivos `.nspawn` e iniciar/detener contenedores.

## 3. La cuenta SSH con capacidad sudo

Cree una cuenta local dedicada en el mismo host, con acceso sudo delimitado, a la que nspawnmgr se
conectará por SSH (siempre por loopback, `127.0.0.1`) para realmente ejecutar
`machinectl`/`systemd-run` y tocar rutas propiedad de root. **Recomendado:** deje que
`packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh` haga esto por usted — es el mismo script
que ejecuta el `postinst` del `.deb`, pero es completamente ejecutable de forma independiente, sin
compilar ni instalar el paquete en absoluto:

```bash
sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
```

Ejecutado desde una copia de trabajo de este repositorio (no se necesitan opciones — autodetecta
los directorios hermanos `privileged-scripts/` y `debian/nspawnmgr.sudoers` junto a sí mismo),
crea la cuenta de sistema `nspawnmgr_exec`, genera y almacena una contraseña aleatoria para ella,
genera un par de claves SSH, instala los scripts envoltorio referenciados más abajo en
`/usr/lib/nspawnmgr/privileged/`, instala y valida la concesión de sudoers, y añade una excepción
`PasswordAuthentication` de sshd para la cuenta si su host lo deshabilita globalmente. Es
idempotente — seguro de volver a ejecutar tras una actualización o para recoger scripts envoltorio
actualizados. Vea el propio comentario de cabecera del script para todo el detalle.

Si prefiere configurar esto completamente a mano en su lugar (p. ej. para usar un nombre de cuenta
diferente), vea lo que hace el propio script como referencia — pero tenga en cuenta los dos niveles
de privilegio de abajo, ya que un `usermod -aG sudo` general (cualquier comando, siempre vía una
contraseña) ya no coincide con cómo nspawnmgr realmente llama a esta cuenta.

### Dos niveles de privilegio

El acceso sudoers para esta cuenta está dividido en dos niveles, no uno:

- **NOPASSWD** — los comandos de forma fija y siempre seguros: `machinectl start/poweroff/
  terminate/reboot/remove/show`, `systemd-run --machine=... --pipe --quiet --wait /bin/sh -s`
  (ejecutar un script de contenedor almacenado — vea "Límite de confianza: scripts de contenedor"
  más abajo para saber por qué esta forma específica de `systemd-run` es NOPASSWD mientras la
  general de abajo no lo es), y los scripts envoltorio bajo `/usr/lib/nspawnmgr/privileged/` que
  gestionan escribir los ajustes de `.nspawn`, eliminar los archivos de un contenedor, y
  sincronizar el cortafuegos de salida. Estas son acciones rutinarias, iniciadas por el
  propietario (iniciar un contenedor, editar sus mapeos de puertos, eliminarlo, ejecutar un
  script que escribió) que nunca deben bloquearse esperando a un administrador, sin importar cuál
  de los modos de creación de contenedores de abajo esté activo.
- **Requiere contraseña** (sin etiqueta `NOPASSWD`) — `systemd-run --machine=... --pipe --quiet
  --wait` (ejecuta contenido arbitrario definido por la plantilla como root dentro de un
  contenedor nuevo — vea "Límite de confianza" más abajo), el envoltorio
  `nspawnmgr-clone-template.sh`, y el envoltorio `nspawnmgr-create-debian-template.sh` (descarga/
  extrae un rootfs Debian real — vea "Plantillas de contenedores" de §2, el botón "Configurar
  debian-minimal" de la página de administración de Plantillas). Los tres solo ocurren en el
  momento de creación — los dos primeros se llaman exactamente una vez por contenedor desde
  `ProvisioningService`, el tercero solo bajo demanda desde un administrador cuando aún no existen
  plantillas. Qué contraseña se usa — y si siquiera hay una disponible sin la participación de un
  administrador — depende del modo de abajo.

Cada comando privilegiado pasa por una de estas dos invocaciones fijas de script
envoltorio/`machinectl`/`systemd-run` — nspawnmgr nunca le pide a sudo que ejecute un script en
línea arbitrario, precisamente para que la concesión de sudoers de arriba pueda coincidir con un
comando/ruta exactos en lugar de tener que hacer coincidir con comodines el texto del script (lo
cual sería frágil: cualquier cambio futuro al contenido del script invalidaría silenciosamente —
o ampliaría silenciosamente en exceso — la concesión).

### Modo de creación de contenedores: secreto almacenado frente a aprobación del administrador

Si crear un contenedor es completamente autoservicio o requiere el visto bueno de un administrador
se **deriva** de si `nspawnmgr.ssh.password`/`SSH_PASSWORD` está configurado — no hay un
conmutador separado:

- **Modo de secreto almacenado / autoservicio** (contraseña configurada, el predeterminado del
  `.deb`): la solicitud "crear contenedor" de un propietario se aprovisiona inmediata y
  automáticamente, igual que antes de que existiera esta función.
- **Modo de aprobación del administrador** (contraseña dejada en blanco): un contenedor nuevo
  llega a un estado `PENDING_APPROVAL` en lugar de aprovisionarse de inmediato. La página
  **Solicitudes** (`/requests` — su elemento de navegación en la barra lateral solo aparece, para
  cualquiera, mientras este modo está activo) lo lista junto a cualquier solicitud pendiente de
  cuenta de usuario dentro de contenedor en una vista combinada. Un administrador ve y puede
  actuar sobre cada elemento pendiente de cada usuario; alguien que no es administrador solo ve
  los suyos propios y puede **Denegarlos** (pasa a un estado terminal `DENIED`, nunca se intenta
  SSH) pero no **Aprobarlos** — aprobar necesita una contraseña de sudo, suministrada en línea,
  usada solo para los pasos en el momento de creación de ese único elemento, mantenida en memoria
  y puesta a cero una vez que esa ejecución se completa, nunca persistida — deliberadamente solo
  se le pide nunca a un administrador.

El inicio de sesión de transporte SSH y la contraseña de sudo comparten el mismo valor configurado,
así que dejar en blanco `SSH_PASSWORD` para seleccionar el modo de aprobación del administrador de
otro modo dejaría a la propia sesión SSH sin nada con qué autenticarse — incluso para el nivel
NOPASSWD de arriba. **El modo de aprobación del administrador por tanto requiere que
`nspawnmgr.ssh.private-key-path`/`SSH_PRIVATE_KEY_PATH` esté configurado**, para que la
autenticación de transporte SSH use una clave en lugar de la contraseña (ahora en blanco).
`setup-sudo-account.sh` genera esta clave incondicionalmente sin importar el modo, así que
cambiar de modo más tarde realmente es solo dejar en blanco/establecer una variable de entorno y
reiniciar — nada más que configurar. nspawnmgr falla al arrancar si no hay configurada ni una
contraseña ni una clave privada en absoluto (`SshPropertiesValidator`), en lugar de mostrar esto
como un fallo de conexión confuso en la primera acción de contenedor.

### Roles de administrador y usuario

El rol de un usuario (`USER`/`ADMIN`) se necesita para controlar la página de aprobación de arriba.
Dos modos, de nuevo seleccionados por si un valor de configuración está establecido — esta vez
`nspawnmgr.auth.user-is-admin-json`:

- **Gestionado por la aplicación** (predeterminado, en blanco): el **primer usuario que inicia
  sesión alguna vez** se promueve automáticamente a `ADMIN`; todos los demás por defecto son
  `USER`. A partir de ahí, cualquier administrador puede promover o degradar a cualquier otro
  usuario en `/admin/users`. Los roles son persistentes — nunca se recalculan silenciosamente al
  iniciar sesión.
- **Gestionado externamente** (`nspawnmgr.auth.user-is-admin-json` establecido a un JsonPath en el
  mismo JSON de identidad que `auth.war` ya devuelve, junto a `user-id-json`/`user-username-json`
  etc.): el rol se recalcula fresco a partir de ese JSON en cada inicio de sesión en su lugar —
  tanto promover como degradar — y la página manual de conceder/revocar rechaza los cambios por
  completo, ya que la fuente de identidad externa es la autoridad en este modo.

### Límite de confianza: comandos de aprovisionamiento definidos en la plantilla

El nivel que requiere contraseña de arriba deja que `systemd-run` ejecute contenido como root
dentro de un contenedor. Ese contenido siempre proviene de uno de: una cadena literal en el propio
`ProvisioningService`, o `Template.installSshCommand`/`installXrdpCommand`. Las plantillas son
editables a través de `/admin/templates`, controladas por el rol ADMIN ya existente en
`/admin/**`, no un flujo de aprobación separado. En otras palabras: **quien tenga el rol ADMIN
controla efectivamente qué se ejecuta como root dentro de cada contenedor creado a partir de una
plantilla que edite.** En el modo de rol gestionado por la aplicación, cualquier administrador
actual puede conceder ADMIN a cualquier otra persona en `/admin/users`, autoservicio, sin ningún
paso de aprobación adicional. Los usuarios ordinarios (no administradores) que han iniciado sesión
todavía no pueden alcanzar esto en absoluto — solo `GET /api/templates` (plantillas activas, solo
resumen) está expuesto fuera de `/admin/**`.

### Límite de confianza: scripts de contenedor

El propietario de un contenedor (o cualquiera con quien se haya compartido ese contenedor — vea
"Compartido con" en la página de detalle del contenedor) puede definir scripts con nombre y
ejecutarlos como root dentro de ese mismo contenedor, vía `/containers/{id}/scripts`. Esta es una
forma de confianza diferente a la edición de plantillas de arriba: el autor es el propio
propietario/usuario-compartido del contenedor, y el script solo se ejecuta nunca dentro de **ese
único contenedor**, nunca en el de nadie más. Esos usuarios ya tienen acceso interactivo completo
de shell root a ese contenedor exacto a través de su propia sesión SSH de Guacamole — ejecutar un
script guardado a través de esta función no concede ningún privilegio que no tuvieran ya; es
puramente una comodidad (con nombre, reutilizable, un clic en lugar de volver a escribirlo por SSH
cada vez). Por eso ejecutar un script es NOPASSWD (`/usr/bin/systemd-run --machine=* --pipe
--quiet --wait /bin/sh -s`, de forma fija, solo ese comando exacto) a diferencia del contenido
definido por la plantilla de arriba, que se ejecuta dentro de los contenedores de *otras* personas
y es escrito por un administrador, no por el propio propietario del contenedor.

**"Compartido con" concede más que acceso a la sesión.** Compartir un contenedor concede al otro
usuario una sesión SSH/RDP de Guacamole *y* la capacidad de crear, editar, eliminar, y ejecutar los
scripts de ese contenedor (acceso root completo, en efecto — vea arriba); no hay un conmutador
separado para conceder uno sin el otro. Si ha compartido contenedores con personas puramente por
comodidad de escritorio remoto, ellas también tienen derechos de script.

### Otras notas de configuración

- Esta cuenta también necesita acceso de lectura/escritura a dondequiera que apunte
  `TEMPLATES_DIR`.
- Debido a que esto es solo por loopback por diseño, nspawnmgr por defecto usa
  `strict-host-key-checking: false` para esta conexión. Solo active eso si alguna vez la apunta a
  un host que no sea localhost, y asegúrese de que la cuenta de Tomcat tenga un
  `~/.ssh/known_hosts` poblado para el destino primero.
- **Todo esto asume que nspawnmgr gestiona contenedores en el mismo host en el que se ejecuta**
  (el único arreglo compatible del `.deb`). Apuntar `nspawnmgr.ssh.host` a un host diferente en su
  lugar es un escenario configurado manualmente, no compatible con las herramientas: tendría que
  repetir de forma independiente la configuración de cuenta/sudoers/par de claves de esta sección
  en ese host remoto usted mismo.
- **El acceso SSH de `nspawnmgr_exec` es solo por loopback por diseño** — no entregue sus
  credenciales a nada fuera de este host. Si quiere que un pipeline externo de CI/CD pueda
  instalar/actualizar plantillas de contenedor, use en su lugar la cuenta `nspawnmgr_ci`, separada
  y deliberadamente más estrecha (vea "Instalación/actualización de plantillas desde un pipeline
  de CI/CD" arriba) — está aislada en su propio archivo sudoers con exactamente una concesión de
  forma fija, a diferencia del amplio acceso NOPASSWD/CONTRASEÑA de `nspawnmgr_exec`, y está
  pensada para alcanzarse por red.

Conectará el nombre de usuario/contraseña (o clave privada) de esta cuenta en la propia
configuración de nspawnmgr como `nspawnmgr.ssh.*` (o `SSH_USERNAME`/`SSH_PASSWORD`/
`SSH_PRIVATE_KEY_PATH`) en [§9](#9-configuración-de-nspawnmgr).

## 4. Base de datos

MySQL, MariaDB, o PostgreSQL — sin opción H2. H2 se usa internamente solo por el conjunto de
pruebas de la pila de desarrollo/CI (una base de datos en memoria, que desaparece en el momento en
que esa JVM se detiene); nunca fue un objetivo de despliegue compatible y no queda ninguna ruta de
código que pueda seleccionarlo como uno. MySQL y MariaDB comparten el mismo controlador JDBC,
esquema, y ubicación de migraciones de Flyway — elegir uno sobre el otro solo cambia a qué nombre
de máquina apunta por defecto el asistente (más abajo), no qué ruta de código se ejecuta.
`spring.datasource.url` y `spring.flyway.locations: classpath:db/migration/<vendor>` deben
coincidir (vea `DB_VENDOR` en la referencia de variables de entorno — siempre `mysql` o
`postgresql`, nunca `mariadb`). Flyway ejecuta las migraciones automáticamente al arrancar;
`spring.jpa.hibernate.ddl-auto` es `validate`, nunca `update` — el esquema es responsabilidad
completa de Flyway.

La base de datos está **autoalojada**, de la misma manera que lo está el propio nspawnmgr
([§1](#1-resumen-de-la-arquitectura)) — el asistente de abajo siempre aprovisiona un contenedor
Debian completamente nuevo para ejecutarla, en lugar de pedirle que apunte a un servidor ya
existente.

### Asistente de configuración del primer arranque

No necesita preparar ninguna base de datos ni establecer
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_VENDOR` usted mismo antes de iniciar Tomcat por primera
vez — este asistente lo hace por usted. Vive en su propio WAR (`ROOT.war`), desplegado en el
contexto raíz de Tomcat dentro de la máquina autoalojada `nspawnmgr`
(`http://<host>:<puerto reenviado>/`, [§1](#1-resumen-de-la-arquitectura)) en lugar de dentro del
propio `nspawnmgr.war`: visitar `/` le redirige directamente a `/nspawnmgr/` una vez que hay una
base de datos funcional configurada, o muestra este asistente en caso contrario. Ir directamente a
`/nspawnmgr/` mientras aún no hay ninguna base de datos configurada simplemente le redirige de
vuelta a `/` — el asistente siempre es el único lugar que decide en qué estado está usted.

Elija un **motor de base de datos** (MySQL, MariaDB, o PostgreSQL) y, opcionalmente, un **nombre de
máquina de base de datos** no predeterminado — por defecto `mysqldb`, `mariadb`, o `postgresdb`
según el motor, editable. También rellene un **nombre de usuario y contraseña iniciales de
nspawnmgr** — una cuenta Linux real, creada dentro de la propia máquina autoalojada `nspawnmgr`,
con la que iniciará sesión una vez que termine la configuración (vea [§8](#8-auth-backend-de-inicio-de-sesión)
para saber por qué esto es todo lo que necesita el propio backend PAM de `auth.war`, sin
configuración adicional).

Al enviar, el asistente:

1. Aprovisiona la máquina de base de datos (`nspawnmgr-bootstrap-db-machine.sh`, ejecutado sobre
   la misma cuenta SSH con capacidad sudo que usa cualquier otra operación privilegiada en esta
   aplicación, vea [§3](#3-la-cuenta-ssh-con-capacidad-sudo)) — clona una plantilla Debian, instala
   el motor elegido (tanto MySQL como MariaDB instalan el propio `mariadb-server` de Debian; no hay
   un paquete Oracle MySQL separado en Debian), y espera a que una unidad systemd de primer arranque
   dentro de esa máquina cree las bases de datos y usuarios `nspawnmgr`/`guacamole` predeterminados
   con contraseñas recién generadas una vez que el motor esté realmente en ejecución (no se intenta
   fuera de línea — ambos motores realmente necesitan ejecutarse brevemente para ejecutar
   `CREATE DATABASE`/`CREATE USER`).
2. Ejecuta las propias migraciones de Flyway de nspawnmgr, luego los scripts de esquema de
   Guacamole (toda instalación siempre parte de una base de datos completamente nueva, así que no
   hay una comprobación de "ya existe un esquema" que ejecutar aquí), y conecta la extensión
   `guacamole-auth-jdbc` de Guacamole por usted (copia el JAR de la extensión a
   `GUACAMOLE_HOME/extensions/` y escribe las propiedades `<vendor>-hostname`/`-port`/`-database`/
   `-username`/`-password` en `GUACAMOLE_HOME/guacamole.properties` — vea "GUACAMOLE_HOME y el
   backend de autenticación" en [§7](#7-guacamole) para saber para qué es eso). Si ese último paso
   falla por algún motivo, no es fatal — la propia base de datos de nspawnmgr (lo que realmente
   decide si este asistente sigue apareciendo) ya está funcionando en ese punto, y el fallo
   simplemente se muestra como una advertencia diciéndole que termine ese único paso a mano.
3. Crea la cuenta Linux inicial de nspawnmgr dentro de la propia máquina autoalojada `nspawnmgr`,
   vía la misma cuenta con capacidad sudo alcanzando de vuelta a esa máquina — el mismo mecanismo
   que ya usa `ProvisioningService` para crear la propia cuenta de inicio de sesión de un
   contenedor gestionado ordinario.
4. Guarda los ajustes de conexión de nspawnmgr que funcionan en
   `/etc/nspawnmgr/db-config/db.properties` dentro de la máquina `nspawnmgr` (propiedad de
   `tomcat:tomcat`, creado automáticamente por `nspawnmgr-bootstrap-app-machine.sh`).

La página de éxito recarga inmediatamente en su sitio tanto el propio contexto de `nspawnmgr.war`
como el de Guacamole — sin botón que pulsar, sin reinicio de Tomcat necesario — tocando
`/opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml` y `guacamole.xml` (el mismo envoltorio
`nspawnmgr-write-file.sh` que usan otras operaciones privilegiadas, ejecutado vía el propio
ayudante SSH sin Spring del asistente ya que todavía no hay contexto de aplicación en este punto
del arranque); el propio hilo de auto-despliegue en segundo plano de Tomcat nota cada cambio y
vuelve a desplegar ese contexto en su sitio. Para `/nspawnmgr` eso vuelve a ejecutar su
comprobación de alcanzabilidad de arranque y arranca la aplicación real esta vez. Guacamole
necesita el mismo tratamiento: en un arranque nuevo su propia aplicación web se inicia (y lee
`guacamole.properties`/carga extensiones, una vez, en ese punto) antes de que un administrador
haya tenido la oportunidad de rellenar este asistente en absoluto — sin también volver a
desplegarla aquí, Guacamole seguiría ejecutándose sin ninguna extensión de autenticación respaldada
por base de datos cargada y rechazaría todo inicio de sesión, incluida la cuenta `guacadmin` que el
propio paso de esquema de este asistente acaba de crear. La página sondea `/nspawnmgr/` y le lleva
allí automáticamente una vez que está activo — normalmente unos segundos, no el reinicio completo
de Tomcat que esto solía requerir.

El propio asistente registra tanto la máquina `nspawnmgr` como su máquina de base de datos como
contenedores ordinarios y visibles en la propia lista de contenedores de nspawnmgr — propiedad de
la cuenta creada en el paso 3 de arriba, con una descripción de "Gestión de máquina virtual"/
"Servidor de base de datos" cada una — directamente en su propio trabajo de base de datos justo
después de las migraciones, sin necesitar inicio de sesión primero (vea ["Descubrir máquinas
creadas fuera de nspawnmgr"](#descubrir-máquinas-creadas-fuera-de-nspawnmgr) para el mismo
mecanismo de registro subyacente, de otro modo activado a mano por un administrador). Cuando
inicie sesión por primera vez (vía esa misma cuenta), simplemente se le reconecta a la identidad de
administrador que el asistente ya creó ([§3](#roles-de-administrador-y-usuario)) — ambas máquinas ya
están ahí esperando. No están ocultas ni tratadas de forma especial después; puede conectarse por
SSH a cualquiera de las dos, compartirlas, eliminarlas, como cualquier otro contenedor — aunque
eliminar la máquina `nspawnmgr` desde la que se está ejecutando actualmente evidentemente no es
una buena idea.

**El propio formulario del asistente no está autenticado y es alcanzable desde cualquier host.** No
hay ninguna base de datos todavía, así que no hay una tabla de usuarios, así que no hay un sistema
de inicio de sesión detrás del cual quedarse — cualquiera que pueda alcanzar este puerto antes de
que la base de datos esté configurada puede configurarla. Restrinja usted mismo el acceso de red a
este puerto (reglas de cortafuegos, manteniéndolo fuera de una interfaz pública hasta que se
complete §4) si eso importa para su despliegue.

## 5. Instalación de nspawnmgr

Dos caminos desde aquí — elija uno. **La opción A (el `.deb`) hace §3 y la mayor parte de §6 por
usted**; la opción B es el recorrido completamente manual desde §6 en adelante. (También existen
paquetes de Arch Linux y Fedora/RHEL, con la misma automatización que la opción A — vea
["Instalación en Arch Linux"](#instalación-en-arch-linux) e ["Instalación en Fedora/RHEL
(RPM)"](#instalación-en-fedorarhel-rpm) justo después.) De cualquier manera, §4 (base de datos), la
configuración de `GUACAMOLE_HOME`/JDBC de Guacamole en §7, los valores de configuración en §9, y la
verificación en §10 siguen siendo su propia responsabilidad — ninguno de los tres paquetes
automatiza más que la *cuenta sudo* y *desplegar los WAR en Tomcat*, no el propio backend de
almacenamiento de Guacamole ni los ajustes a nivel de aplicación de nspawnmgr.

**Lo que necesita para *compilar* cada formato de paquete no es lo mismo que necesita para
*instalarlo*** — vale la pena saberlo antes de elegir un camino, especialmente si la máquina en la
que está compilando no es la misma en la que va a desplegar:

| Formato | Necesita para compilar | Necesita para instalar | ¿Compilable de forma cruzada? |
|---|---|---|---|
| `.deb` (`packaging/nspawnmgr-deb/`) | JDK 21 + Maven (el plugin `jdeb` es Java puro) | `apt`, Debian/Ubuntu | **Sí** — compile en cualquier host con un JDK, incluidos Arch/Fedora/Windows/macOS |
| Arch (`packaging/nspawnmgr-arch/`) | JDK 21 + Maven, **más `makepkg`/`base-devel`** | `pacman`, Arch Linux | **No** — `makepkg` es una herramienta nativa de Arch sin equivalente multiplataforma; el host de compilación debe ser Arch en sí mismo (o la imagen de contenedor `archlinux/devtools`) |
| RPM (`packaging/nspawnmgr-rpm/`) | JDK 21 + Maven, **más `rpm-build`** | `dnf`, Fedora/RHEL | **No** — a pesar de la reputación de `rpm-maven-plugin`, genuinamente ejecuta externamente un binario `rpmbuild` real; confirmado en vivo que falla directamente en un host de compilación que no sea RPM (p. ej. Windows) sin equivalente multiplataforma, la misma historia que `makepkg` de Arch |

Si no tiene una máquina Arch o Fedora de repuesto en la que compilar esto,
`packaging/ci/arch-runner/bootstrap-arch-runner.sh` y `packaging/ci/fedora-runner/
bootstrap-fedora-runner.sh` muestran una forma de obtener cualquiera de los dos sin arranque dual
ni hardware físico: ambos hornean un rootfs real en un contenedor `systemd-nspawn` plano (no una
imagen Docker/Podman — nspawn resultó ser lo más simple aquí, ya que comparte el espacio de nombres
de red del host por defecto en lugar de necesitar su propio puente solo para CI). Los trabajos
`arch-package` y `rpm-package` de `.gitea/workflows/build.yml` muestran los comandos de compilación
exactos que se ejecutan una vez que cada contenedor existe (instale el JDK/Maven/herramientas
nativas de empaquetado, luego `BUILD_ARCH_PKG=1`/`BUILD_RPM=1 tools/scripts/build-all.sh`, igual
que se muestra abajo).

### Opción A: el paquete .deb (recomendado)

Solo Debian/Ubuntu para el **host** — las máquinas autoalojadas `nspawnmgr`/base de datos que crea
siempre son Debian sin importar cuál, según [§1](#1-resumen-de-la-arquitectura). Se encarga de §3
(la cuenta con capacidad sudo, sudoers, par de claves SSH) y crea+arranca la máquina autoalojada
`nspawnmgr` con Tomcat, los cuatro WAR, y `guacd` ya instalados dentro de ella — el *resto* de §6
no es omisible, sin embargo: "Habilitar HTTPS" y "Usar un puerto diferente" en particular todavía
valen la pena leerse (vea "Qué sigue siendo manual después de esto" más abajo), simplemente
aplicadas dentro de esa máquina ahora en lugar de en el host. Continúe a §7 una vez que esté
instalado.

**Obtenga un `.deb`**, ya sea compilando uno usted mismo:

```bash
mvn -DskipTests install                          # root -> target/nspawnmgr.war (installed, not just packaged - the next module needs it)
mvn -f auth/pom.xml -DskipTests package          # -> auth/target/auth.war
mvn -f packaging/nspawnmgr-deb/pom.xml package   # -> packaging/nspawnmgr-deb/target/nspawnmgr_*.deb
```

(o `BUILD_DEB=1 tools/scripts/build-all.sh`, que hace los mismos tres pasos — esa variable de
entorno existe porque compilar un `.deb` necesita acceso a la red para obtener el plugin Maven
`jdeb` la primera vez que se usa, algo a lo que una compilación de desarrollo normal no debería
verse forzada), o instalando uno ya compilado desde donde sea que lo publique su equipo — la propia
CI de este repositorio (el trabajo `publish-deb` de `.gitea/workflows/build.yml`) publica cada
compilación exitosa en un registro de paquetes Debian de Gitea como referencia funcional si quiere
configurar lo mismo para su propio fork/instancia (necesita un secreto de Actions del repositorio
`PACKAGE_REGISTRY_TOKEN`, un token de acceso de Gitea con alcance de escritura de paquetes — vea el
propio comentario de ese trabajo en el archivo de flujo de trabajo).

**Instálelo:**

```bash
sudo apt install ./nspawnmgr_0.4.0_all.deb   # pulls in openssh-server, openssl, dnsmasq, systemd-container - not a JRE, not tomcat9
```

Ni `tomcat9` ni `guacd`/`guacamole-tomcat` están en el `Depends:` de este paquete — la propia
disponibilidad de `tomcat9` en apt varía lo suficiente según la versión, y `guacd`/
`guacamole-tomcat` no están empaquetados en ninguna versión actual en absoluto (vea la propia nota
de `packaging/nspawnmgr-deb/debian/control`). `tomcat9`, `guacd`, y `guacamole.war` vienen todos
incluidos en su lugar y no necesitan nada de usted (vea §6 y §7) — el único paso manual que queda
en §7 es la extensión de autenticación respaldada por base de datos, ya que eso genuinamente
necesita credenciales que solo usted tiene.

**Lo que acaba de suceder, automáticamente** (vea `packaging/nspawnmgr-deb/debian/postinst` y
`nspawnmgr-bootstrap-app-machine.sh` para los scripts exactos):

- Se creó una cuenta de sistema `nspawnmgr_exec` en el **host**; se generó una contraseña aleatoria
  para ella (solo en la primera instalación — sin tocar en una actualización) y se escribió en
  `/etc/nspawnmgr/nspawnmgr.env` (esta es la contraseña de sudo de "secreto almacenado" de §3 —
  vea §3 para saber qué significa eso y cómo cambiar al modo de aprobación del administrador en su
  lugar); se generó un par de claves SSH y se instaló en el `authorized_keys` de esa cuenta sin
  importar el modo. La división de sudoers NOPASSWD/nivel-de-contraseña de §3 →
  `/etc/sudoers.d/nspawnmgr_exec`, validada con `visudo -cf` antes de confiar en ella.
- El puente compartido (`nspawnbr0`) y dnsmasq se configuraron en el host, igual que para
  cualquier otro contenedor gestionado — vea "Resolución de contenedores por nombre" arriba.
- Se horneó `debian-minimal` (el mismo tarball que produciría "Configurar debian-minimal" en
  `/admin/templates`) y se clonó en una máquina nueva llamada `nspawnmgr`.
- Mientras todavía era solo un rootfs extraído, sin arrancar aún: un JRE, el tarball incluido de
  Apache Tomcat 9.0.120, los cuatro WAR (`nspawnmgr.war`/`auth.war`/`guacamole.war`/`ROOT.war`), y
  el paquete autocontenido de `guacd` (su propio OpenSSL 3.x, un FFmpeg mínimo, FreeRDP2, libssh2)
  se instalaron directamente en el propio sistema de archivos de esa máquina — se crearon usuarios
  de sistema `tomcat`/`guacd` dentro de ella, se eliminaron las aplicaciones web
  `manager`/`host-manager`/`examples`/`docs`, se sembró `GUACAMOLE_HOME` con un
  `guacamole.properties` mínimo apuntando al propio `guacd` de esa misma máquina, y se extrajeron
  `guacamole-auth-jdbc` más ambos jars de controlador JDBC (todo sin necesitar acceso a la red —
  todo incluido, nada descargado).
- Se escribió una copia reescrita de `/etc/nspawnmgr/nspawnmgr.env` en esa máquina (`SSH_HOST` y
  `HOST_PUBLIC_ADDRESS` reapuntados a la propia dirección de `nspawnbr0` en lugar de `127.0.0.1`,
  para que nspawnmgr pueda alcanzar de vuelta la cuenta `nspawnmgr_exec` del host una vez que
  arranca), junto con una copia de la clave privada SSH.
- Se eligió un puerto libre del host (`8080` primero, incrementando más allá de cualquiera que ya
  esté en uso — impreso durante la instalación) y se reenvió al propio `:8080` de esa máquina vía
  una línea `Port=` en su archivo `.nspawn`, así que `http://<este host>:<ese puerto>/` alcanza
  nspawnmgr exactamente como siempre lo ha hecho una instalación no autoalojada.
- Se inició la máquina. Tomcat dentro de ella arranca sirviendo el asistente de base de datos del
  primer arranque de `ROOT.war` (§4) — aún no hay ninguna base de datos configurada en este punto,
  igual que antes, simplemente alcanzable en una dirección subyacente diferente ahora.

**Compruebe que se instaló correctamente:**

```bash
sudo machinectl list                             # should show "nspawnmgr" running
sudo visudo -cf /etc/sudoers.d/nspawnmgr_exec    # should print "parsed OK"
curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port shown during install>/
```

Nada relacionado con Tomcat se ejecuta ya en el propio host — no busque `tomcat9.service` ni
`/opt/tomcat9` ahí; ambos viven ahora dentro de la máquina `nspawnmgr` (`sudo machinectl shell
nspawnmgr` para mirar dentro de ella, o use el propio acceso SSH de nspawnmgr a ella una vez que
haya iniciado sesión — vea la nota de §4 sobre cómo aparece en la lista de contenedores). El `.deb`
nunca escribe `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` en el `nspawnmgr.env` de esa máquina — solo los
ajustes de sudo/nombre de host — así que la comprobación curl de arriba:

- **`200`** — todavía no hay base de datos funcional, así que está mirando el asistente de
  configuración del primer arranque descrito en "Asistente de configuración del primer arranque"
  de §4. Este es el estado normal justo después de una instalación nueva del `.deb`; rellene el
  asistente para continuar.
- **`302`** (redirección a `/nspawnmgr/`) — ya hay una base de datos funcional configurada. Sígala
  y espere otro `302` (a la página de inicio de sesión) si la aplicación real arrancó
  normalmente, o un `404` si no lo hizo: el contexto Spring de nspawnmgr falló al arrancar.
  Compruebe `sudo machinectl shell nspawnmgr journalctl -u tomcat9` antes de asumir que el
  propio paquete está roto (la propia página "Ver log" de la interfaz web de nspawnmgr no
  ayudará aquí — nspawnmgr en sí nunca llegó lo bastante lejos como para arrancar); normalmente
  es un valor ausente/incorrecto en el propio `/etc/nspawnmgr/nspawnmgr.env` de esa máquina (§9
  cubre qué significa cada ajuste).

**Qué sigue siendo manual después de esto**: apuntar el asistente del primer arranque (§4) a un
servidor MySQL/PostgreSQL — crea tanto la base de datos `nspawnmgr` como la `guacamole`, ejecuta
los esquemas de ambas aplicaciones, y conecta la extensión `guacamole-auth-jdbc` de Guacamole por
usted, pero todavía necesita ejecutarlo una vez y todavía necesita crear la cuenta de administrador
de Guacamole después; al menos una plantilla de contenedor (la "Plantillas de contenedores" de §2 —
no se puede crear nada hasta que exista una; una instalación nueva empieza con cero, así que el
botón de un clic "Configurar debian-minimal" de `/admin/templates` está disponible de inmediato);
revisar/ajustar el resto de `/etc/nspawnmgr/nspawnmgr.env` contra §9 (URL base de Guacamole, etc.
— el archivo generado rellena la credencial de sudo, `APP_SECRET_KEY`, y
`USER_ID_URL`/`AUTH_LOGIN_URL` apuntados al propio `auth.war` incluido de este host, pero no la
configuración de aplicación que no tiene un valor predeterminado autogenerado sensato), habilitar
HTTPS ("Habilitar HTTPS" de §6 — el `.deb` deja Tomcat en HTTP plano por defecto, igual que el
camino manual; muy recomendado si usa el modo de aprobación del administrador, según esa sección),
y la verificación (§10).

`postrm` deliberadamente nunca elimina `nspawnmgr_exec` ni `/etc/nspawnmgr` al eliminar/purgar el
paquete — esa cuenta es la única credencial a través de la cual sus contenedores son alcanzables.

**Para actualizar una instalación existente a una compilación de paquete más nueva** (una
corrección de errores, no una instalación nueva): `sudo /usr/lib/nspawnmgr/upgrade-nspawnmgr.sh
<ruta-al-nuevo-archivo-de-paquete>`. Un simple `apt install`/`dnf install`/`pacman -U` — o incluso
`apt install --reinstall` — no es suficiente por sí solo: estos pueden no hacer nada
silenciosamente si la cadena de versión instalada registrada no ha cambiado, lo cual importa ya que
cada compilación dentro de un ciclo de desarrollo se vuelve a publicar bajo la misma versión fija.
Este script instala el archivo de paquete dado directamente en su lugar (siempre aplica su
contenido, sin importar la versión registrada), lo cual a su vez vuelve a disparar la propia
postinstalación del paquete — y eso siempre llama a `nspawnmgr-bootstrap-app-machine.sh`, que
reconcilia por completo el contenido de la máquina autoalojada `nspawnmgr` en cada llamada, no solo
en la primera instalación: los cuatro WAR incluidos, el propio paquete y servicio de `guacd`, la
unidad de servicio de Tomcat, y el archivo de credencial de conexión SSH de vuelta se refrescan
todos, y la máquina se detiene/reinicia alrededor de eso para que nada se sobrescriba mientras
todavía está en uso. Su puerto reenviado del host existente se conserva a través de la
actualización, no se vuelve a elegir. No destructivo — `/var/lib/machines` (cualquier *otro*
contenedor) y ambas bases de datos se dejan completamente en paz; el clon del rootfs base y las
cuentas de sistema `tomcat`/`guacd` dentro de la máquina también se dejan en paz (volver a
tocarlas podría destrozar una personalización real del administrador, o fallar directamente en una
segunda ejecución) — un aumento de *versión* de Tomcat específicamente todavía necesita una
reinstalación completa, igual que antes.

**Para eliminar todo de todos modos** (máquinas de prueba, empezar de nuevo desde cero — no algo
que ejecutar en un despliegue real sin pensarlo primero, ya que elimina las credenciales de
sudo/SSH a través de las cuales sus contenedores siguen siendo alcanzables):
`sudo /usr/lib/nspawnmgr/uninstall-nspawnmgr.sh`. Más allá de lo que ya hace `apt purge`, también
elimina `/opt/tomcat9`, `/etc/nspawnmgr`, `/etc/guacamole`, `/var/lib/nspawnmgr/templates`
(`TEMPLATES_DIR` — tarballs de plantilla, incluyendo cualquier cosa que el botón "Configurar
debian-minimal" haya descargado; un archivo de plantilla sobrante que sobreviva a una purga es
exactamente lo que hace que falle la comprobación de "no debe existir ya" de ese botón en una
reinstalación posterior), las cuentas de sistema `tomcat`/`nspawnmgr_exec`, y cualquier [ajuste de
arranque de máquina](#inicio-automático-al-arrancar-el-host) que haya configurado nspawnmgr
(habilitación de la unidad de autoarranque, el fragmento de requiere-otra-máquina) — eso es puro
estado de archivo de unidad de systemd con clave solo por nombre de máquina, sin tocar por
`apt purge` ni siquiera al eliminar los propios contenedores, y un fragmento `Requires=` obsoleto
que sobreviva de una instalación anterior es suficiente para romper directamente una reinstalación
nueva (`machinectl start nspawnmgr` fallando con "A dependency job for
systemd-nspawn@nspawnmgr.service failed." porque la unidad que requería ya no existía) — todo aquí
es lo que `postrm` deliberadamente deja atrás, para los casos en que ese conservadurismo no es lo
que quiere. Por defecto todavía **no** toca la propia base de datos de nspawnmgr, la propia base de
datos de Guacamole, ni `/var/lib/machines` (sus contenedores reales) — solo la capa de gestión
alrededor de ellos (más las plantillas usadas para crearlos) — pero por separado pregunta (su
propio aviso s/n cada uno, nunca implicado por `--yes`) si también eliminar las bases de datos
`nspawnmgr`/`guacamole` y sus usuarios de BD (solo compatible cuando `DB_URL` apunta a
`localhost`/`127.0.0.1`, leído de `db.properties`/`nspawnmgr.env` antes de que se eliminen esos
archivos) y si eliminar cada contenedor actualmente registrado con `machinectl`. Útil para
reiniciar rápidamente un host de prueba real entre iteraciones, ya que esos dos pasos son pérdida
de datos real.

### Instalación en Arch Linux

Compilación e instalación ambas verificadas en vivo en sistemas reales de la familia Arch:
`makepkg -f` contra este `PKGBUILD` exacto (el contenedor systemd-nspawn `arch-runner` en acer —
vea `packaging/ci/arch-runner/`) produce un `nspawnmgr-0.3.0-1-any.pkg.tar.zst` real vía el
trabajo `arch-package` de `.gitea/workflows/build.yml`, y los propios hooks `pacman -U` +
`nspawnmgr.install` del paquete resultante se han ejercitado repetidamente en un sistema SteamOS
real (basado en Arch, compatible con `pacman` una vez que se ejecuta `steamos-readonly disable`) —
instalaciones nuevas, ciclos de desinstalación/reinstalación, y actualizaciones en el sitio vía
`upgrade-nspawnmgr.sh` se han confirmado todas funcionando, incluyendo que la máquina autoalojada
arranque con una concesión de red real y que la interfaz web responda correctamente. Existe un
paquete **separado**, `packaging/nspawnmgr-steamos/`, específicamente para SteamOS (vea sus
propios `provides`/`conflicts` contra este — instale exactamente uno de los dos, nunca ambos) ya
que la pequeña partición raíz de SteamOS necesita el almacenamiento reubicado bajo `/home`; este
paquete Arch plano es lo que debería instalar en su lugar un host Arch que no sea SteamOS. Ese
camino no-SteamOS — instalar este paquete exacto en Arch genuinamente vainilla (a diferencia de
SteamOS, que comparte la misma mecánica subyacente de `pacman`/`systemd` pero no es idéntico) —
todavía no se ha probado directamente; informe qué falla si lo intenta.

`packaging/nspawnmgr-arch/` (un `PKGBUILD` + `nspawnmgr.install`, no un módulo Maven — no existe
ningún plugin de empaquetado de Arch nativo de Maven) es por lo demás la misma arquitectura
autoalojada que la opción A de arriba, solo un formato de paquete diferente: la misma configuración
de cuenta/sudoers/puente/dnsmasq `nspawnmgr_exec`, la misma máquina autoalojada `nspawnmgr`
(todavía Debian-mínima sin importar la propia distribución de este host — vea
[§1](#1-resumen-de-la-arquitectura) — un host Arch no cambia lo que ejecuta la propia *máquina de
aplicación* autoalojada, solo lo que necesita el propio *host desnudo*), el mismo "Lo que acaba de
suceder", "Compruebe que se instaló correctamente", y "Qué sigue siendo manual después de esto" que
la opción A — lea eso arriba, se aplica aquí sin cambios. Las diferencias son estrechas:

- **Dependencias**: `openssh`, `openssl`, `dnsmasq` — sin JRE, sin equivalente de
  `apache2-utils` (ambos se instalan *dentro* de la máquina de aplicación autoalojada, no
  necesarios en el host desnudo en absoluto — vea `nspawnmgr-bootstrap-app-machine.sh`), sin
  equivalente de `systemd-container` (`machinectl`/`systemd-nspawn` vienen incluidos ya en el
  propio paquete base `systemd` de Arch).
- **Sin paso de cortafuegos**: a diferencia de la excepción DHCP `ufw` del `.deb`, Arch no viene
  con ningún cortafuegos habilitado por defecto, así que no hay nada que sortear. Si ha configurado
  `nftables`/`iptables`/`ufw` usted mismo, asegúrese de que UDP/67 entrante en `nspawnbr0` esté
  permitido (el mismo requisito por el que existe el propio paso `ufw` del `.deb`).
- **La eliminación sigue siendo conservadora por defecto**: `pacman -R`/`-Rns` no da la misma
  distinción purga-frente-a-eliminar que sí dan `dpkg`/`apt`, así que el propio `post_remove()` de
  `nspawnmgr.install` deliberadamente hace tan poco como el propio comportamiento predeterminado
  (no purga) de `postrm` — el mismo script `uninstall-nspawnmgr.sh` que el `.deb` gestiona la
  limpieza completa, todavía instalado en la misma ruta.

Compilar e instalar:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_ARCH_PKG=1 tools/scripts/build-all.sh   # needs `makepkg` on PATH - a real Arch host, or the
                                               # archlinux/devtools container image

sudo pacman -U packaging/nspawnmgr-arch/nspawnmgr-0.4.0-1-any.pkg.tar.zst
```

### Instalación en Fedora/RHEL (RPM)

Compilación e instalación ambas verificadas en vivo en un host real Fedora 43 bajo SELinux
`Enforcing` (el contenedor systemd-nspawn `fedora-runner` en acer para compilar — vea
`packaging/ci/fedora-runner/` — y una `fedora-test-vm` QEMU separada para la verificación de
instalación): el flujo real de extremo a extremo (asistente de configuración de BD, inicio de
sesión, creación de contenedores, y actualizaciones repetidas en el sitio vía
`upgrade-nspawnmgr.sh`) se ha confirmado funcionando, incluso bajo SELinux Enforcing
específicamente.

`packaging/nspawnmgr-rpm/` (un módulo Maven real — `rpm-maven-plugin` genuinamente ejecuta
externamente `rpmbuild`, no es Java puro a pesar de las apariencias) es por lo demás la misma
arquitectura autoalojada que la opción A de arriba — la misma configuración de
cuenta/sudoers/puente/dnsmasq `nspawnmgr_exec`, la misma máquina autoalojada `nspawnmgr` (todavía
Debian-mínima sin importar la propia distribución de este host), el mismo "Lo que acaba de
suceder", "Compruebe que se instaló correctamente", y "Qué sigue siendo manual después de esto" que
la opción A. Las diferencias son estrechas:

- **Dependencias**: `openssh-server`, `openssl`, `dnsmasq`, `systemd-container`, y
  `iptables-nft` — el paquete de Fedora respaldado por nftables que realmente proporciona
  `/usr/bin/iptables` (el nombre de paquete `iptables` plano no existe en Fedora; el conmutador
  de internet saliente por contenedor necesita un binario `iptables` real sin importar el
  backend).
- **Excepción de firewalld**: Fedora viene con `firewalld` activo por defecto. Instalar añade
  `nspawnbr0` a la zona `trusted` de firewalld y recarga — sin esto, la política de zona
  predeterminada de firewalld bloquea silenciosamente las concesiones DHCP a los contenedores, la
  misma forma de fallo que la propia excepción de `firewalld` de SteamOS (más abajo).
- **Módulo de política SELinux**: bajo el modo `Enforcing`, `systemd_machined_t` necesita un
  pequeño módulo de política personalizado (`nspawnmgr_machined_cgroup.te`, compilado desde el
  código fuente en el momento de la instalación vía `checkmodule`/`semodule_package`/
  `semodule -i` en lugar de distribuirse como un `.pp` precompilado, para que coincida con la
  versión de política que realmente se está ejecutando) que concede `watch` en archivos
  `cgroup_t` — un hueco general de política SELinux en cualquier host Fedora Enforcing estándar,
  no específico de nspawnmgr, que de otro modo rompe todo inicio de contenedor
  `machinectl`/`systemd-nspawn` con "Failed to register machine: Access denied."
- **La eliminación sigue siendo conservadora por defecto**, misma postura y mismo script
  `uninstall-nspawnmgr.sh` que los otros dos formatos de paquete.

Una advertencia de topología de entorno, no un error de código: el nombre de host
autodetectado de `AUTH_LOGIN_URL` necesita ser resoluble desde donde sea que el navegador
realmente se conecte (una elección de diseño deliberada — vea [§9](#9-configuración-de-nspawnmgr)
— que evita un bucle de inicio de sesión peor con ámbito de cookie). Esto puede causar problemas
específicamente al probar a través de una topología de NAT/túnel/reenvío-de-puerto en lugar de un
nombre de host real directamente alcanzable; ajuste `AUTH_LOGIN_URL` a mano en ese caso.

Compilar e instalar:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_RPM=1 tools/scripts/build-all.sh   # needs a real `rpmbuild` binary (`rpm-build` package) -
                                          # a real Fedora/RHEL host, no cross-platform equivalent

sudo dnf install ./packaging/nspawnmgr-rpm/target/rpm/noarch/nspawnmgr-0.4.0-1.noarch.rpm
```

### Opción B: compilar desde el código fuente, desplegar manualmente

**Este camino despliega Tomcat directamente en el host en el que está trabajando — no autoaloja
nspawnmgr en su propia máquina de la manera en que lo hace la opción A.** Está bien; el
autoalojamiento es una elección con criterio propio que hace el `postinst` del `.deb`, no un
requisito estricto — un despliegue construido manualmente, con Tomcat en el host, sigue siendo
totalmente compatible, simplemente es la topología más antigua/simple. Si quiere el modelo
autoalojado sin el `.deb`, el camino más directo es leer `nspawnmgr-bootstrap-app-machine.sh` y
hacer a mano lo que hace (hornear una plantilla, clonarla, instalar un JRE/Tomcat/los WAR en el
rootfs de ese contenedor, etc.) en lugar de seguir el §6 de abajo, que despliega Tomcat en el propio
host, igual que siempre lo ha hecho.

Desde la raíz del repositorio:

```bash
mvn -DskipTests package                # -> target/nspawnmgr.war
mvn -f auth/pom.xml -DskipTests package  # -> auth/target/auth.war
```

(`tools/scripts/build-all.sh` hace ambos, más los módulos simulados solo de desarrollo — las
simulaciones no se necesitan para un despliegue real.) Continúe a §6 para la configuración manual
de Tomcat/cuenta/sudoers que de otro modo habría hecho el `.deb` por usted.

El `postinst` del `.deb` también crea `/etc/nspawnmgr/auth-live/`, propiedad de `tomcat:tomcat`
modo `750` — el archivo compartido al que `/admin/settings` escribe la configuración en vivo de
auth.war (vea [§9](#9-configuración-de-nspawnmgr)). Un despliegue manual necesita lo mismo, una
vez que exista el usuario `tomcat` de Tomcat (§6):

```bash
sudo mkdir -p /etc/nspawnmgr/auth-live
sudo chown tomcat:tomcat /etc/nspawnmgr/auth-live
sudo chmod 750 /etc/nspawnmgr/auth-live
```

## 6. Tomcat 9 (nspawnmgr + Guacamole + auth)

**Esta sección describe desplegar Tomcat directamente en el host** — la forma que adopta una
instalación manual (§5 opción B). Si instaló vía el paquete `.deb`/Arch/RPM (§5 opción A), Tomcat
no está en el host en absoluto — está dentro de la máquina autoalojada `nspawnmgr`, ya configurado
por `nspawnmgr-bootstrap-app-machine.sh`, y nada de esta sección aplica; vaya directamente a §7.

La aplicación web oficial de Guacamole todavía apunta a `javax.servlet`, así que ella y nspawnmgr
se despliegan uno al lado del otro en la **misma instancia de Tomcat 9**.

**No es una dependencia de apt.** Como `guacd` (§7), la propia disponibilidad del paquete apt
`tomcat9` varía lo suficiente según la versión de Debian/Ubuntu/Mint como para que este proyecto
incluya una distribución binaria de Apache Tomcat original en lugar de depender de él — una
versión de parche actual (9.0.120), no lo que sea que lleve un archivo apt, y este paquete es
dueño de toda la instancia él mismo (`/opt/tomcat9`, su propio usuario de sistema `tomcat`, su
propio `tomcat9.service`). **Si ya hay instalada una versión anterior de este paquete (que sí
dependía del `tomcat9` de apt), elimine primero el `tomcat9` de ese paquete** — dos instancias de
Tomcat ambas intentando vincularse a `:8080` fallarán.

De lo contrario (opción B), extraiga el mismo tarball incluido que distribuye el `.deb` —
`packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz` en una copia de trabajo del
repositorio — en lugar de descargar una copia nueva usted mismo, para que una instalación manual
coincida con la versión de parche exacta contra la que se prueba este proyecto:

```bash
sudo mkdir -p /opt/tomcat9
sudo tar -xzf packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz -C /opt/tomcat9 --strip-components=1
sudo chmod +x /opt/tomcat9/bin/*.sh
```

Ejecute Tomcat como su propio usuario de sistema sin privilegios, sin sudo (nunca root, y
deliberadamente no la misma cuenta que [§3](#3-la-cuenta-ssh-con-capacidad-sudo)):

```bash
sudo useradd -r -M -d /opt/tomcat9 -s /usr/sbin/nologin tomcat
sudo chown -R tomcat:tomcat /opt/tomcat9
```

**Si hizo [§3](#3-la-cuenta-ssh-con-capacidad-sudo) antes de esto** (el orden documentado), vuelva
y haga que el par de claves SSH que generó (`SSH_PRIVATE_KEY_PATH`, por defecto
`/etc/nspawnmgr/ssh_id_ed25519`) sea legible por este usuario `tomcat` ahora que existe —
`SshRemoteExecutor` abre ese archivo directamente desde dentro del propio proceso de Tomcat en
cada operación privilegiada, y la clave se crea `root:root` modo `600` (sin ningún acceso de grupo
en absoluto) ya que `tomcat` todavía no existe en ese punto:

```bash
sudo chown root:tomcat /etc/nspawnmgr/ssh_id_ed25519
sudo chmod 640 /etc/nspawnmgr/ssh_id_ed25519
```

Saltarse esto deja fallando toda operación privilegiada con "Failed to establish SSH connection
to 127.0.0.1:22" — un problema de permisos, no de conectividad, a pesar de la redacción.

El tarball original incluye las aplicaciones web `manager`/`host-manager`/`examples`/`docs` que el
propio paquete `tomcat9` de Debian divide en subpaquetes separados, no instalados por defecto; el
`postinst` del `.deb` los elimina en la primera instalación por el mismo motivo — superficie de
ataque real y evitable si se dejan desplegados sin configurar — vale la pena hacerlo a mano aquí
también:

```bash
sudo rm -rf /opt/tomcat9/webapps/manager /opt/tomcat9/webapps/host-manager \
       /opt/tomcat9/webapps/examples /opt/tomcat9/webapps/docs
```

Despliegue nspawnmgr:

```bash
sudo cp target/nspawnmgr.war /opt/tomcat9/webapps/nspawnmgr.war
```

nspawnmgr, Guacamole, y `auth` (§8) toman cada uno su propia ruta de contexto de abajo — ninguno de
ellos puede reclamar la raíz del servidor sin renunciar a esa ruta — así que coloque una pequeña
página de redirección estática para el `http://<hostname>:8080/` desnudo, usando el propio
`site/root-index/index.html` de este repositorio como referencia (redirige a `/nspawnmgr/`):

```bash
sudo mkdir -p /opt/tomcat9/webapps/ROOT
sudo cp site/root-index/index.html /opt/tomcat9/webapps/ROOT/index.html
sudo chown -R tomcat:tomcat /opt/tomcat9/webapps/ROOT
```

Establezca `SPRING_PROFILES_ACTIVE=prod` (más cualquier otra variable de entorno de
[§9](#9-configuración-de-nspawnmgr)) en lo que sea que envuelva el arranque de Tomcat (el
`Environment=`/`EnvironmentFile=` de una unidad systemd, o `bin/setenv.sh` bajo `CATALINA_OPTS` —
entrecomille cada valor `-D` si contiene un `;`, ya que `catalina.sh` vuelve a evaluar
`$CATALINA_OPTS` como una línea de comandos de shell y un `;` sin escapar se analiza como un
separador de comandos, truncando silenciosamente el lanzamiento). Sin ningún perfil activo,
nspawnmgr por defecto usa `dev` (H2 en memoria, ejecutores simulados) — no lo que quiere aquí.

Configúrelo como un servicio systemd para que sobreviva a los reinicios, p. ej.
`/etc/systemd/system/tomcat9.service` (la misma unidad que instala el `.deb` —
`packaging/nspawnmgr-deb/tomcat9.service` en una copia de trabajo del repositorio es una
referencia ya lista):

```ini
[Unit]
Description=Apache Tomcat 9 (bundled by nspawnmgr)
After=network.target

[Service]
Type=simple
ExecStart=/opt/tomcat9/bin/catalina.sh run
ExecStop=/opt/tomcat9/bin/catalina.sh stop
User=tomcat
Group=tomcat
Restart=on-failure
RestartSec=2
EnvironmentFile=/etc/nspawnmgr/nspawnmgr.env

[Install]
WantedBy=multi-user.target
```

`Type=simple` con `catalina.sh run` (en primer plano) en lugar de `Type=forking` con
`startup.sh`/`shutdown.sh` — systemd supervisa la JVM directamente de esta manera, así que un fallo
se detecta y `Restart=on-failure` realmente se dispara; una unidad forking solo sabe si el propio
*script envoltorio* salió, no si Tomcat mismo sigue vivo.

```bash
sudo systemctl enable --now tomcat9
```

### Usar un puerto diferente

Tomcat escucha en `8080` por defecto (`<Connector port="8080" .../>` de `conf/server.xml`). Para
cambiarlo, edite ese atributo `port` directamente:

```bash
sudo sed -i 's/port="8080"/port="8180"/' /opt/tomcat9/conf/server.xml
```

O use la sección **Tomcat** en `/admin/settings` en lugar de editar `server.xml` a mano — lee/
escribe el mismo archivo (localizado vía la propiedad de sistema JVM `catalina.base` que el propio
script de arranque de Tomcat siempre establece, así que encuentra el `server.xml` correcto tanto si
está ejecutando el `tomcat9` empaquetado en Debian del `.deb` como uno extraído manualmente bajo
`/opt/tomcat9`), pasando por la misma cuenta SSH con capacidad sudo y el script envoltorio
`nspawnmgr-write-file.sh` que ya usa cualquier otra operación privilegiada — no se necesita ninguna
concesión de sudoers nueva. Es el **propio archivo el que es autoritativo**, no una copia en la
base de datos: la página siempre muestra y edita lo que sea que realmente esté en disco, así que
editar `server.xml` a mano directamente (como arriba) y usar la página de ajustes son totalmente
intercambiables — ninguno de los dos queda desactualizado respecto al otro.

Cualquier otro `:8080` en esta guía (y en su propia configuración —
`nspawnmgr.auth.user-id-url`/`AUTH_LOGIN_URL`, `nspawnmgr.guacamole.base-url`, y cualquier URL que
les diga a los usuarios que visiten) debe actualizarse para coincidir — nada deriva el puerto
automáticamente de `server.xml`, sea cual sea la forma en que lo cambie. En `/admin/settings` esto
es en su mayoría un clic por campo: cada uno de esos campos de URL tiene un botón "Actualizar nombre
de host/puerto/protocolo" que lo reescribe a partir del propio estado actual de puerto/HTTPS de la
sección Tomcat más `host.external-hostname` (§8) — sin necesidad de editar a mano el puerto de cada
URL por separado. Si está detrás de un cortafuegos, asegúrese de que el nuevo puerto esté abierto
en lugar de `8080`. De cualquier manera, el cambio solo tiene efecto después de un reinicio — use
el botón Reiniciar Tomcat en `/admin/settings` (vea arriba) o `sudo systemctl restart tomcat9`
usted mismo.

### Habilitar HTTPS

Dos opciones, en orden de cómo lo hacen realmente la mayoría de los despliegues reales:

1. **Terminar TLS con un proxy inverso** (nginx, Apache, Caddy, un balanceador de carga en la
   nube) delante de Tomcat, que sigue escuchando en HTTP plano solo en `127.0.0.1:8080` (vincúlelo
   a loopback en `<Connector address="127.0.0.1" .../>` de `server.xml` para que no sea alcanzable
   directamente). Este suele ser el camino más fácil para la renovación de certificados (p. ej.
   Certbot/Let's Encrypt) ya que está desacoplado del propio formato de keystore de Tomcat. Apunte
   cada URL `nspawnmgr.*`/`AUTH_LOGIN_URL` de esta guía a `https://<hostname>/...` (el puerto que
   sea que escuche el proxy) en lugar de `http://<hostname>:8080/...` — el proxy, no Tomcat, es a
   lo que realmente aplican los requisitos de nombre de host/cookie en
   [§8](#nombre-de-host-y-la-cookie-de-sesión-compartida).

2. **Configurar un conector SSL de Tomcat directamente**, si prefiere no ejecutar un proxy
   inverso. Desde Tomcat 8.5/9, el elemento `<Certificate>` de `<SSLHostConfig>` acepta un
   certificado/clave PEM directamente
   (`certificateFile`/`certificateKeyFile`/`certificateChainFile`) — sin necesitar conversión a
   keystore Java, lo cual importa porque es exactamente el formato que le entregan los clientes
   Let's Encrypt/ACME (p. ej. Certbot) (`fullchain.pem`/`privkey.pem`). Apunte Certbot a este host
   (`certbot certonly --standalone -d nspawnmgr.example.com`, o cualquier plugin que se ajuste a su
   configuración) y añada un conector a `server.xml`:

   ```xml
   <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
              SSLEnabled="true" scheme="https" secure="true" maxThreads="150">
       <SSLHostConfig>
           <Certificate certificateFile="/etc/letsencrypt/live/nspawnmgr.example.com/fullchain.pem"
                        certificateKeyFile="/etc/letsencrypt/live/nspawnmgr.example.com/privkey.pem"
                        type="RSA"/>
       </SSLHostConfig>
   </Connector>
   ```

   El usuario de sistema `tomcat` necesita acceso de lectura a
   `/etc/letsencrypt/live/.../*.pem` (los propios directorios de Let's Encrypt suelen ser solo de
   root por defecto — o bien relaje los permisos solo en esos dos archivos o cópielos a algún
   sitio que Tomcat pueda leer, y vuelva a copiarlos en cada renovación). Reinicie Tomcat, y luego
   use `https://<hostname>:8443/...` en toda esta guía en lugar de `http://<hostname>:8080/...`.
   Elimine completamente el conector HTTP plano o establezca su `redirectPort="8443"` para que una
   solicitud HTTP perdida se redirija a HTTPS en lugar de servirse en claro. La renovación de
   Certbot no reinicia Tomcat por usted — añada un `--deploy-hook "systemctl restart tomcat9"` (o
   un script en `renewal-hooks/deploy/`) para que un certificado renovado realmente surta efecto.

   La sección **Tomcat** en `/admin/settings` construye/edita exactamente este bloque de conector
   por usted — un desplegable "HTTPS" más las dos rutas PEM — usando el mismo mecanismo de
   archivo-como-autoridad y script-envoltorio-SSH descrito en "Usar un puerto diferente" arriba.
   Nunca elimina el conector HTTP plano ni establece `redirectPort` por usted, y siempre reemplaza
   por completo las rutas del elemento `<Certificate>` existente al guardar en lugar de
   fusionarlas — si ha personalizado el conector más allá de lo mostrado aquí (un tipo de
   certificado que no sea `RSA`, múltiples entradas `SSLHostConfig`, etc.), edite `server.xml` a
   mano en su lugar.

Cualquiera que sea la opción que elija, cada URL `http://` referenciada en otras partes de esta
guía — incluso dentro de `application.yml`/variables de entorno, no solo lo que ve un navegador —
necesita convertirse en `https://` para coincidir; un desajuste entre con qué está configurado
nspawnmgr y qué se sirve realmente es una causa común de bucles de redirección o fallos de
cookie-no-enviada.

**Si está usando el modo de aprobación del administrador**
([§3](#3-la-cuenta-ssh-con-capacidad-sudo)), habilitar HTTPS aquí se recomienda encarecidamente
incluso si nada más se lo sugirió: la página de aprobación envía la contraseña de sudo de un
administrador como un campo de formulario plano, y eso es una exposición significativamente mayor
sobre HTTP plano que cualquier otra cosa que sirva nspawnmgr. La instalación predeterminada
documentada permanece en HTTP — esto es una recomendación para ese modo específico, no un cambio
al predeterminado.

## 7. Guacamole

**Ninguno de los tres componentes de Guacamole es un paquete apt en ninguna versión actual de
Debian/Ubuntu/Mint**: `guacd` y `guacamole-tomcat` devuelven cero resultados en bookworm, trixie,
jammy, y noble, e incluso Debian unstable solo compila `guacd` para `ia64`/`riscv64`, no `amd64`.
Cada uno se gestiona de forma diferente, y ninguno por sí solo le da una configuración funcional:

| Componente | ¿Empaquetado? | Qué hace |
|---|---|---|
| `guacd` | **No.** El `.deb` incluye en su lugar una compilación autocontenida (su propio OpenSSL 3.x, un FFmpeg mínimo, FreeRDP2, y libssh2 — vea `/usr/share/doc/nspawnmgr/guacd-bundle-README.md` para saber exactamente por qué y cómo) y lo ejecuta como su propia unidad systemd `guacd.service` — sin paquete de sistema, sin paso manual, en ninguna opción de instalación. | el propio daemon proxy nativo |
| `guacamole-tomcat` | **No.** Tampoco incluido (es el *pegamento de empaquetado* que normalmente le desplegaría `guacamole.war`) — pero el propio `guacamole.war` sí lo está: el `.deb` lo despliega directamente en el Tomcat incluido, igual que `nspawnmgr.war`/`auth.war` (vea abajo). | despliega `guacamole.war` en Tomcat automáticamente |
| `guacamole-auth-jdbc` | **No.** No es un paquete apt, pero incluido de la misma manera que `guacd` — un tarball descargado una vez, verificado con checksum, y confirmado en `packaging/nspawnmgr-deb/vendor/` (vea `vendor/README.md`), no obtenido de nuevo en el momento de la instalación. El `postinst` del `.deb` lo extrae automáticamente, sin necesitar red; las instalaciones manuales ejecutan el mismo script a mano (vea abajo). **Obligatorio, no opcional** — vea abajo. | la extensión JDBC que le da a Guacamole un backend de almacenamiento de conexiones MySQL/PostgreSQL, más sus scripts de esquema SQL |

`guacamole-auth-jdbc` no es una opción entre varios backends que podría elegir en su lugar —
nspawnmgr gestiona cada conexión y usuario de Guacamole a través de la API REST de Guacamole (vea
"GUACAMOLE_HOME y el backend de autenticación" más abajo), y esa API solo existe cuando Guacamole
está ejecutando una extensión de autenticación respaldada por base de datos. El propio
predeterminado de Guacamole (`user-mapping.xml`, un archivo XML estático sin API) no la expone.
Saltarse este paso no le da un nspawnmgr funcional con funcionalidad reducida — le da un nspawnmgr
que no puede crear ni gestionar ninguna conexión de contenedor en absoluto, ya que toda acción de
"dar a este usuario acceso a este contenedor" en última instancia llama a esta API. Incluso con la
automatización del `.deb`, extraer el tarball es solo la mitad de lo que describe el paso 1 de §7
más abajo — el JAR/controlador todavía se tienen que copiar a mano en `GUACAMOLE_HOME`, y ni que
`guacd` ni `guacamole.war` estén desplegados implica que algo de esto esté hecho; confírmelo por
separado.

### guacd

Si instaló vía el `.deb` (§5 opción A), esto ya está hecho —
`nspawnmgr-bootstrap-app-machine.sh` extrajo el paquete autocontenido a `/opt/guacd-bundle` e
inició `guacd.service` **dentro de la propia máquina autoalojada `nspawnmgr`**, no en el host
(`sudo machinectl shell nspawnmgr systemctl status guacd` para confirmar) — y salte a "guacamole.war"
más abajo.

De lo contrario (opción B, despliegue de Tomcat en el host — [§6](#6-tomcat-9-nspawnmgr--guacamole--auth)),
necesita un binario `guacd` real de algún sitio, ya que apt no proporcionará uno en ninguna versión
actual. El camino más directo es reutilizar la misma compilación autocontenida que distribuye el
`.deb`: `packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz` en una copia de trabajo del
repositorio (o compile su propia copia siguiendo la receta de
`packaging/nspawnmgr-deb/vendor/README.md` — documenta cada paso, incluyendo dos escollos reales
que costaron tiempo real de encontrar: CMake almacenando en caché silenciosamente una ruta de
OpenSSL obsoleta entre reconfiguraciones, y `-Wl,-rpath` no siendo suficiente por sí solo sin un
`-L` a juego). Extráigalo e instale la unidad systemd de la misma manera que lo hace `postinst`:

```bash
sudo tar -xzf packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz -C /opt
sudo adduser --system --home /nonexistent --no-create-home --group guacd
sudo cp packaging/nspawnmgr-deb/guacd.service /etc/systemd/system/guacd.service
sudo systemctl daemon-reload
sudo systemctl enable --now guacd
```

### guacamole.war

Si instaló vía el `.deb` (§5 opción A), esto también ya está hecho —
`nspawnmgr-bootstrap-app-machine.sh` desplegó `packaging/nspawnmgr-deb/vendor/guacamole-1.5.5.war`
(la misma versión oficial de Apache, descargada una vez y verificada con checksum, no obtenida de
nuevo en el momento de la instalación) vía un descriptor de contexto apuntando a
`/usr/share/nspawnmgr/guacamole.war` **dentro de la propia máquina autoalojada `nspawnmgr`**, junto
a `nspawnmgr.war`/`auth.war`. Confirme con
`curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<puerto reenviado>/guacamole/` (espere
`200`, o una redirección al propio flujo de inicio de sesión de Guacamole) y salte a
"GUACAMOLE_HOME y el backend de autenticación" más abajo.

De lo contrario (opción B, despliegue de Tomcat en el host), descargue y despliegue el mismo
archivo usted mismo:

```bash
GUACAMOLE_VERSION=1.5.5
curl -fsSL -o guacamole.war \
  "https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/guacamole-${GUACAMOLE_VERSION}.war"
sudo cp guacamole.war /opt/tomcat9/webapps/guacamole.war
```

### GUACAMOLE_HOME y el backend de autenticación

Guacamole necesita su propio `GUACAMOLE_HOME` (comúnmente `/etc/guacamole`) que contenga
`guacamole.properties` más el JAR de la extensión `guacamole-auth-jdbc`/el controlador JDBC para
su **backend de almacenamiento de conexiones** — esto es una preocupación separada de la propia
base de datos de nspawnmgr. **El asistente de base de datos del primer arranque de §4 ahora hace
los pasos 1–2 de abajo automáticamente** (copiando dentro el JAR de extensión correcto, escribiendo
las propiedades `<vendor>-*`, ejecutando el esquema) como parte de configurar la base de datos
`guacamole` — el recorrido de abajo es para hacerlo a mano en su lugar (sin acceso al asistente, la
conexión automática falló y dejó una advertencia, o está cambiando la base de datos después del
hecho). Si instaló vía el `.deb`, este directorio y un `guacamole.properties` mínimo (solo
`guacd-hostname`/`guacd-port`, apuntado a la instancia de `guacd` que ya inició la misma
instalación) ya existen, propiedad de `tomcat:tomcat` — creado una vez, solo en la primera
instalación, así que una edición posterior (a mano o vía el editor de Guacamole de
`/admin/settings`) siempre sobrevive a una actualización. De lo contrario (opción B), créelo usted
mismo: `sudo mkdir -p /etc/guacamole && sudo chown tomcat:tomcat /etc/guacamole`. Como se cubrió
arriba, la propia extensión de autenticación JDBC es obligatoria, no una elección entre
alternativas: nspawnmgr gestiona conexiones/usuarios a través de la API REST de Guacamole usando
una cuenta de administrador (`nspawnmgr.guacamole.admin-username`/`admin-password`), y solo
`guacamole-auth-jdbc` expone esa API. Así que:

1. Consiga que se extraiga el tarball de `guacamole-auth-jdbc` — a diferencia de
   `guacd`/`guacamole-tomcat` de arriba, no hay un paquete apt para esto en ninguna versión, pero
   al igual que `guacd` viene incluido directamente en lugar de descargarse en el momento de la
   instalación: `packaging/nspawnmgr-deb/vendor/guacamole-auth-jdbc-1.5.5.tar.gz` en una copia de
   trabajo del repositorio es el mismo tarball que distribuye el `.deb`, ya descargado una vez y
   verificado con checksum contra el propio `.sha256` de Apache. `install-guacamole-auth-jdbc.sh`
   lo extrae (sin necesitar red) en una **ubicación de instalación con criterio propio**, fija e
   independiente de la versión, `/etc/guacamole/guacamole-auth-jdbc/` (subcarpetas `mysql/schema/`
   y `postgresql/schema/`, sin importar cuál base de datos termine usando — el tarball incluye
   ambas). Esta no es una ruta que la propia Guacamole requiera, solo una convención propia de
   nspawnmgr:
   - **Instalaciones `.deb`**: esto ya se ejecutó automáticamente, como parte de `postinst` — si
     falló (p. ej. el tarball de alguna manera falta en `/usr/share/nspawnmgr/`), vuelva a
     ejecutar `sudo /usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh` a mano.
   - **Instalaciones manuales**, o para rehacerlo (p. ej. para subir la versión de Guacamole —
     primero vuelva a incluir el tarball): ejecute
     `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh` desde una copia de
     trabajo del repositorio (opciones `--source-tarball`/`--target-dir`/`--force` disponibles —
     vea el propio comentario de cabecera del script).

   De cualquier manera, desde `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/`, copie el
   JAR de extensión para su base de datos elegida (`nspawnmgr.guacamole.data-source`, p. ej.
   `mysql`) a `GUACAMOLE_HOME/extensions/` — todavía un paso manual, ya que depende de una
   elección (qué base de datos) que nada puede hacer por usted.

   El propio controlador JDBC (el `java.sql.Driver` real, separado del JAR de extensión de arriba
   — `guacamole-auth-jdbc` nunca lo incluye) es una historia diferente: nspawnmgr.war ya incluye
   tanto el controlador de MySQL como el de PostgreSQL para su propio uso de base de datos, no
   relacionado (`pom.xml` raíz), así que en lugar de una segunda descarga separada,
   `install-guacamole-jdbc-drivers.sh` simplemente copia ambos jars de controlador ya compilados
   del propio nspawnmgr a `GUACAMOLE_HOME/lib/` — sin necesitar ningún acceso a la red en absoluto,
   y sin ningún perjuicio en que ambos estén ahí aunque solo se use uno realmente. Al igual que el
   tarball de esquema de arriba, esto ya se ejecutó automáticamente como parte del `postinst` del
   `.deb` (de mejor esfuerzo — vuelva a ejecutar
   `sudo /usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh` si falló por algún motivo); para una
   instalación manual, ejecute
   `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh --source-dir target/guacamole-jdbc-drivers`
   desde una copia de trabajo del repositorio después de `mvn -DskipTests package`.
2. Ejecute el script de esquema de esa extensión contra una base de datos que sea propiedad de
   Guacamole (esta **no** es la misma base de datos que la propia de nspawnmgr — Guacamole
   necesita su propio esquema de usuarios/conexiones). La sección de Guacamole en
   `/admin/settings` tiene un botón **"Probar conexión de base de datos"** que hace esto por
   usted: se conecta con lo que sea que esté actualmente introducido en los campos de Base de
   datos, comprueba si el esquema parece configurado (sondeando la tabla
   `guacamole_connection`), y si no, ofrece ejecutar cada archivo `.sql` en un directorio que le
   indique — el campo "Directorio de scripts de esquema" ya tiene por defecto
   `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/schema` (coincidiendo con el tipo de
   base de datos seleccionado encima de él), así que esto normalmente es un clic en "Probar" sin
   editar nada si el paso 1 usó la ubicación con criterio propio.
3. Cree la cuenta de administrador que usará nspawnmgr (`guacadmin`/`guacadmin` es el
   predeterminado bien conocido con el que viene la extensión JDBC en la primera ejecución —
   cambie la contraseña de inmediato en un despliegue real, y actualice
   `nspawnmgr.guacamole.admin-password` para que coincida).
4. Establezca `guacd-hostname`/`guacd-port` en `guacamole.properties` (por defecto
   `localhost:4822`, está bien si guacd se ejecuta en el mismo host).

Reinicie Tomcat después de colocar archivos en `GUACAMOLE_HOME` — Guacamole no recarga en caliente
las extensiones.

Apunte nspawnmgr a ello (`nspawnmgr.guacamole.base-url`) una vez que esté activo, p. ej.
`http://your-hostname:8080/guacamole`. También establezca `nspawnmgr.guacamole.home`
(`GUACAMOLE_HOME`, por defecto `/etc/guacamole`) si usó una ruta no predeterminada — esto es de
donde el editor de Guacamole de `/admin/settings` lee/escribe `guacamole.properties` (vea
[§9](#9-configuración-de-nspawnmgr)). Sin necesitar configuración de permisos adicional:
nspawnmgr y Guacamole ambos se ejecutan como el mismo usuario `tomcat` en la misma instancia de
Tomcat, y `GUACAMOLE_HOME` ya es propiedad de `tomcat` para el propio uso de Guacamole.

## 8. `auth` (backend de inicio de sesión)

`auth.war` es lo que realmente comprueba un nombre de usuario/contraseña contra sus cuentas del
sistema operativo (PAM) o una máquina Windows por SMB, y emite la cookie de sesión compartida en
la que confía nspawnmgr. Apunta a `javax.servlet` (Servlet 4.0), lo mismo que nspawnmgr y la
aplicación web de Guacamole, así que se despliega en la **misma instancia de Tomcat 9** de §6 —
sin necesitar un contenedor de servlets separado. (Solo para iteración local rápida, también se
puede ejecutar de forma independiente vía `mvn -f auth/pom.xml jetty:run`, que lo inicia en Jetty
en el puerto 9092 sin un ciclo de recompilación/redespliegue de WAR — no es algo que usaría para un
despliegue real.)

Establezca esto vía parámetros de contexto en `auth/src/main/webapp/WEB-INF/web.xml` (vuelva a
compilar el WAR después de editar) o las propiedades de sistema correspondientes (`-D...`),
documentadas en ese archivo:

| Ajuste | Propiedad de sistema | Propósito |
|---|---|---|
| `auth.backend` | `AUTH_BACKEND` | `pam` (predeterminado, cuentas Linux locales en el propio host de auth) o `smb` (máquina Windows remota) |
| `smb.server` | `SMB_SERVER` | Obligatorio si `auth.backend=smb` — el host Windows contra el que autenticar |
| `smb.domain` | `SMB_DOMAIN` | Dominio NTLM opcional |
| `auth.required-group` | `AUTH_REQUIRED_GROUP` | Opcional, solo `pam` — un grupo Unix; se rechaza el inicio de sesión de usuarios autenticados que no sean miembros |
| `smb.required-share` | `SMB_REQUIRED_SHARE` | Opcional, solo `smb` — un recurso compartido SMB en `smb.server`; se rechaza el inicio de sesión a menos que el usuario tenga acceso a él (vea abajo por qué esto es una comprobación de recurso compartido, no una comprobación de grupo) |
| `cookie.name` | — | Debe coincidir con `nspawnmgr.auth.cookie-name` de nspawnmgr (por defecto `nspawnmgr_session`) |

**Por qué `smb` controla el acceso mediante el recurso compartido, no la pertenencia a un grupo:**
Windows restringe las consultas *remotas* de SAM/grupos a `BUILTIN\Administrators` por defecto
(`RestrictRemoteSAM`) — esto excluiría a los usuarios normales de pasar nunca una comprobación de
grupo, por diseño, sin importar los ajustes del registro. El acceso a un recurso compartido es una
operación SMB normal, controlada por ACL, sin tal restricción, así que conceda/deniegue el acceso
estableciendo permisos normales de recurso compartido y NTFS en `smb.required-share` para los
usuarios que deberían/no deberían poder iniciar sesión.

**`pam` necesita que la cuenta de Tomcat tenga acceso de lectura a `/etc/shadow`.** Verificar una
contraseña vía PAM en última instancia significa leer el hash del usuario objetivo de
`/etc/shadow` (modo `640`, `root:shadow`) — normalmente gestionado de forma transparente a través
del propio ayudante `unix_chkpwd` setgid-`shadow` de `pam_unix`, sin importar el grupo del propio
proceso llamante, pero ese respaldo no es fiable en todos los hosts (una instalación real dio con
exactamente esto: la promoción setgid de `unix_chkpwd` silenciosamente no surtió efecto para
*ningún* llamante que no fuera root en absoluto, así que todo inicio de sesión PAM fallaba con un
simple "Login failed" y ningún error accionable en el propio log de `auth.war`). El `postinst` del
`.deb` añade `tomcat` al grupo `shadow` directamente (`usermod -aG shadow tomcat`) para sortear
esto — `pam_unix` puede entonces leer `/etc/shadow` él mismo, sin necesitar el respaldo de
`unix_chkpwd` de todos modos. Una instalación manual (no `.deb`) necesita lo mismo:
`sudo usermod -aG shadow tomcat`, luego reinicie Tomcat (la pertenencia a un grupo solo aplica a
procesos iniciados *después* del cambio, no a uno ya en ejecución). Si los inicios de sesión PAM
fallan después de eso, compruebe `/var/log/auth.log` para ver la propia línea real de
`pam_unix(login:auth)` — es la forma más directa de ver qué rechazó realmente PAM, ya que la propia
página "Login failed" de `auth.war` es deliberadamente genérica (sin pistas de enumeración de
credenciales).

Despliéguelo en su propia ruta de contexto `/auth` en la misma instancia de Tomcat 9 que
nspawnmgr/Guacamole (que toman `/nspawnmgr` y `/guacamole`) para que sirva `/auth/login`,
`/auth/userinfo`, `/auth/logout` (coincidiendo con `nspawnmgr.auth.user-id-url` de abajo):

```bash
sudo cp auth/target/auth.war /opt/tomcat9/webapps/auth.war
```

`tools/scripts/setup-auth-tomcat.sh` es una referencia para exactamente esto, adaptada para
pruebas locales. Las propias páginas de inicio/cierre de sesión de `auth` construyen sus enlaces
internos (p. ej. "Try again") a partir de `request.getContextPath()`, no una ruta fija, así que se
resuelven correctamente sin importar si se despliega en `/auth` aquí o en la raíz del servidor
(p. ej. vía `jetty:run` para iteración local).

### Nombre de host y la cookie de sesión compartida

nspawnmgr, `auth`, y Guacamole **deben ser todos alcanzables a través del mismo nombre de host** —
la cookie de sesión que establece `auth` solo es útil para nspawnmgr si ambos están en el mismo
ámbito de cookie de origen. Dado que los tres ahora comparten una instancia de Tomcat, esto es en
gran medida automático (mismo host, mismo puerto), pero de todos modos elija un nombre de host real
(no `localhost`, a menos que todo realmente esté en una sola máquina a la que solo accederá siempre
como `localhost`), apúntelo a la IP del host en DNS o `/etc/hosts`, y establézcalo una vez en
**`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME` — editable en vivo en
`/admin/settings`, "Nombre de host externo" bajo Host; sembrado automáticamente con el nombre de
host real de esta máquina por `setup-sudo-account.sh` en instalaciones `.deb`, vea §5). Este *no*
es el mismo ajuste que `nspawnmgr.host.public-address` justo debajo de él en esa página — vea la
propia descripción de ese campo, o [§9](#9-configuración-de-nspawnmgr), para la diferencia.

Cualquier otro sitio donde este nombre de host necesite aparecer es un campo de URL plano, no
derivado automáticamente — `nspawnmgr.auth.user-id-url`
(`http://<hostname>:8080/auth/userinfo`), `nspawnmgr.guacamole.base-url`, y la página de inicio de
sesión que se les dice a los administradores/usuarios que visiten
(`http://<hostname>:8080/auth/login?returnTo=...`) — pero `/admin/settings` cierra ese hueco: cada
uno de esos campos de URL tiene un botón **"Actualizar nombre de host/puerto/protocolo"** que lo
reescribe a partir del Nombre de host externo de arriba más el propio estado actual de
puerto/HTTPS de la sección Tomcat (vea §6), así que un cambio de nombre de host o puerto solo se
tiene que escribir en un sitio antes de hacer clic a través del resto.

Si termina HTTPS delante de esto, el CN/SAN del certificado debe coincidir con ese nombre de host —
un desajuste aquí es la causa más común de "el inicio de sesión funciona pero nspawnmgr sigue
mostrando la página de inicio de sesión requerido."

**Siempre navegue a nspawnmgr a través del mismo nombre de host que
`HOST_EXTERNAL_HOSTNAME`/`AUTH_LOGIN_URL` — no `localhost`, una dirección IP, ni ningún otro alias,
incluso si resuelve a la misma máquina.** La cookie que emite `auth.war` no tiene ningún atributo
`Domain`, así que tiene el ámbito del host:puerto exacto que sirvió la página de inicio de sesión —
lo que sea que apunte `AUTH_LOGIN_URL`, no cualquiera que sea el nombre de host que escribió
originalmente. La redirección al inicio de sesión de nspawnmgr siempre envía `returnTo` de vuelta a
ese mismo host:puerto también (sin importar con qué nombre de host empezó), así que un desajuste
aquí no da bucles para siempre, pero terminará en el nombre de host canónico en lugar del que
escribió — lo más simple es simplemente usar siempre el correcto desde el principio.

### La redirección de nspawnmgr → auth

Cuando nspawnmgr no puede validar una cookie de sesión, redirige el navegador a
`nspawnmgr.auth.login-url` (variable de entorno `AUTH_LOGIN_URL`) con un parámetro de consulta
`returnTo` apuntando de vuelta a la página que el usuario intentaba alcanzar; `auth.war` redirige
de vuelta ahí después de un inicio de sesión exitoso. Si `login-url` se deja en blanco, nspawnmgr
en su lugar muestra su propia página estática de "inicio de sesión requerido" sin redirección —
establezca `AUTH_LOGIN_URL` a la URL `/auth/login` de `auth` (p. ej.
`http://<hostname>:8080/auth/login`) para el flujo automático completo.

## 9. Configuración de nspawnmgr

Todos los ajustes viven bajo `nspawnmgr.*` en `src/main/resources/application.yml`, cada uno
anulable por una variable de entorno — vea `site/env/.env.example` para la lista completa como
variables de entorno, y `dev_env/application-dev_env.example.yml` para los mismos ajustes como
YAML. Los grupos importantes:

- **`nspawnmgr.ssh.*`** — la cuenta con capacidad sudo de
  [§3](#3-la-cuenta-ssh-con-capacidad-sudo) (`SSH_HOST`/`SSH_PORT`/`SSH_USERNAME`/`SSH_PASSWORD`,
  host siempre `127.0.0.1`), más `SSH_PRIVATE_KEY_PATH`, `SSH_CONNECT_TIMEOUT_MS`,
  `SSH_STRICT_HOST_KEY_CHECKING`. Dejar `SSH_PASSWORD` en blanco cambia la creación de contenedores
  al modo de aprobación del administrador y requiere que se establezca `SSH_PRIVATE_KEY_PATH` en
  su lugar (la autenticación de transporte SSH necesita *algo* con qué autenticarse de todos
  modos).
- **`nspawnmgr.auth.user-is-admin-json`** — JsonPath opcional para roles de administrador
  gestionados externamente ([§3](#3-la-cuenta-ssh-con-capacidad-sudo)); déjelo en blanco para el
  modo predeterminado gestionado por la aplicación (el primer usuario en iniciar sesión se
  convierte en administrador, gestionable después en `/admin/users`).
- **`nspawnmgr.guacamole.*`** — `base-url`, `admin-username`/`admin-password`, `data-source`,
  `home` (`GUACAMOLE_HOME`, por defecto `/etc/guacamole`), de [§7](#7-guacamole).
- **`nspawnmgr.auth.*`** — `user-id-url` (valida una cookie existente contra `auth`),
  `cookie-name`, `login-url` (el destino de redirección de §8), ajuste de caché/tiempo de espera,
  `settings-file` (dónde se escribe el archivo de ajustes de autenticación compartido de abajo —
  debe coincidir con el propio `auth.settings-file`/`AUTH_SETTINGS_FILE` de auth.war, por defecto
  `/etc/nspawnmgr/auth-live/auth-settings.properties`).
- **`nspawnmgr.nspawn.*`** — `templates-dir`, `machines-dir`, `settings-dir`,
  `privileged-scripts-dir` de [§2](#2-requisitos-previos-del-host).
- **`nspawnmgr.dns.upstream-servers`** — literales IP separados por comas a los que dnsmasq reenvía
  las búsquedas que no son `.internal`, por defecto `1.1.1.1,9.9.9.9` — vea ["Resolución de
  contenedores por nombre"](#resolución-de-contenedores-por-nombre). `hosts-file`/
  `upstream-servers-file` (qué archivos escribe `ContainerDnsSyncService`) son rutas del momento de
  despliegue, no editables en vivo.
- **`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME`) — el nombre de host compartido
  de [§8](#nombre-de-host-y-la-cookie-de-sesión-compartida); lo que usan los usuarios fuera de
  este host, y lo que los botones "Actualizar" de URL de `/admin/settings` incorporan en cada URL
  de Guacamole/Auth.
- **`nspawnmgr.host.public-address`** (`HOST_PUBLIC_ADDRESS`) — un ajuste diferente, fácilmente
  confundible con el de arriba, ya no usado por la ruta SSH/RDP (`guacd` y la propia comprobación
  de disponibilidad de nspawnmgr ahora marcan directamente la dirección veth interna de un
  contenedor GESTIONADO en su lugar — vea [Redes de contenedores](#redes-de-contenedores)). Su
  único consumidor restante es la comprobación "HOST_PUBLIC_ADDRESS not loopback" de la página de
  Diagnóstico de red; si esa comprobación todavía se justifica vale la pena echarle un vistazo de
  seguimiento, pero aún no se ha revisado. `setup-sudo-account.sh` todavía autodetecta y siembra
  la dirección real de este host aquí en la instalación.
- **`nspawnmgr.crypto.secret-key`** (`APP_SECRET_KEY`) — genérela con `openssl rand -base64 32`;
  se usa para cifrar secretos que almacena nspawnmgr (p. ej. credenciales de Guacamole que
  gestiona por contenedor). Perder/rotar esto invalida cualquier cosa ya cifrada con la clave
  antigua.
- **`nspawnmgr.provisioning.*`** — `admin-account-name` (la cuenta de respaldo que nspawnmgr crea
  dentro de un contenedor nuevo cuando no se puede usar el propio nombre de usuario de su
  propietario — vea `Container users` más abajo), `rdp-password-length`.
- **`CONTAINER_CLI_EXECUTOR=real`** — debe ser `real` para un despliegue real; `fake` es solo
  para desarrollo/CI, y nunca toca SSH/sudo/contraseñas en absoluto sin importar el modo de
  creación de contenedores de arriba. Selecciona qué beans de Spring se conectan en el arranque
  del contexto, así que no se puede cambiar en tiempo de ejecución en absoluto — no expuesto en
  `/admin/settings`, deliberadamente: esta es una elección del momento de despliegue, y dado lo
  que hace `fake` (toda operación de contenedor se convierte en una no-operación silenciosa), no
  vale la pena el riesgo de exponerlo como un conmutador en tiempo de ejecución.

Establezca `SPRING_PROFILES_ACTIVE=prod` — esto activa los ejecutores reales respaldados por SSH
en lugar de las simulaciones en memoria usadas para el desarrollo local.

### Ajustes editables en vivo (`/admin/settings`)

Un subconjunto de los grupos de arriba también se puede cambiar en tiempo de ejecución en
`/admin/settings` (solo administrador): `guacamole.base-url`/`data-source`,
`host.external-hostname`/`public-address`, cada campo `auth.*` incluyendo `http-timeout-ms`,
`provisioning.admin-account-name`/`rdp-password-length`, `nspawnmgr.ssh.*`, `nspawnmgr.nspawn.*`,
y `nspawnmgr.dns.upstream-servers`. Estos tienen efecto inmediato para cada solicitud/asignación
posterior — `SettingsService` mantiene una instantánea en memoria refrescada en el momento en que
se guarda un cambio, no una lectura de base de datos por solicitud. Una excepción, señalada en la
propia página:

- **`nspawnmgr.nspawn.privileged-scripts-dir`** tiene efecto inmediato como todo lo demás en su
  grupo, pero cambiarlo *sin también actualizar* las rutas fijas del propio
  `/etc/sudoers.d/nspawnmgr_exec` para que coincidan rompe **toda** operación privilegiada
  (iniciar/detener contenedor, sincronización de acceso saliente, Reiniciar Tomcat de abajo) —
  sudo falla de forma segura, simplemente rechazando la ruta nueva, en lugar de seguir este
  ajuste. No hay validación en vivo para este en particular (es una ruta local, posiblemente ni
  siquiera creada todavía en el momento de guardar) — solo la advertencia mostrada en la página.
- **`nspawnmgr.dns.upstream-servers`** tiene efecto en la propia instantánea de `SettingsService`
  de inmediato como todo lo demás, pero alcanzar el propio dnsmasq en ejecución real está un paso
  más allá de eso: `ContainerDnsSyncService` solo recoge el valor nuevo, reescribe
  `/etc/dnsmasq.d/nspawnmgr-upstream.conf`, y reinicia dnsmasq en su propio sondeo de ~15s — vea
  ["Resolución de contenedores por nombre"](#resolución-de-contenedores-por-nombre) para saber por
  qué eso es un `systemctl restart` completo, no solo una recarga.

**Todo lo demás permanece estático/de variable de entorno/solo-con-reinicio**, deliberadamente:
`nspawnmgr.crypto.secret-key`/`nspawnmgr.guacamole.admin-username`/`admin-password` (secretos, más
el hecho de que rotar la clave criptográfica en vivo invalidaría cualquier cosa ya cifrada con la
antigua), y `CONTAINER_CLI_EXECUTOR` (vea arriba). Los Hosts no son en absoluto un ajuste estático —
se gestionan completamente por el administrador vía la propia página de detalle de cada host y
`/admin/hosts/new` (vea "Hosts: máquinas externas gestionadas por el administrador" arriba).

Cada cambio se valida antes de aceptarse:
- **URL base de Guacamole, URL de ID de usuario de auth, URL de inicio de sesión de auth**: un
  sondeo de alcanzabilidad HTTP en vivo (cualquier respuesta, incluso un 404, cuenta como
  alcanzable — esto solo prueba que la URL resuelve a algo escuchando, no que la propia
  autenticación tenga éxito).
- **Los cinco campos JsonPath**: deben compilar como expresiones JsonPath válidas.
- **Dirección pública del host**: solo de formato (sintaxis de nombre de host/IP) —
  deliberadamente *no* sondeada, ya que una dirección pública a menudo solo es alcanzable desde
  fuera de este host; auto-sondearla no probaría nada.
- Nombre de cookie, TTL de caché, nombre de cuenta de administrador, y longitud de contraseña RDP
  reciben comprobaciones básicas de formato/rango.
- **`dns.upstream-servers`**: debe ser una lista separada por comas de literales IP (IPv4 o IPv6)
  — se rechaza un nombre de host, ya que la propia directiva `server=` de dnsmasq necesita uno que
  ya sea resoluble sin ningún servidor DNS en absoluto (es lo que el propio dnsmasq usa para
  resolver todo lo demás).
- **`ssh.*`**: si algún campo SSH está presente en el cambio enviado, se abre una conexión SSH
  real con los ajustes *resultantes* (solo inicio de sesión de transporte — sin ejecución de
  comandos, así que esto no depende de que la concesión de sudoers NOPASSWD sea correcta) antes de
  que se acepte el cambio. La página de ajustes siempre reenvía cada campo junto (como cualquier
  otra sección aquí), así que en la práctica esto se ejecuta en cada guardado desde la interfaz —
  la misma manera en que ya lo hacen los sondeos de alcanzabilidad de URL de Guacamole/auth
  existentes. Llamar a la API directamente con una carga parcial que omite cada clave `ssh.*` se
  lo salta.

#### Sección de autenticación (condicional a que se detecte auth.war)

Si auth.war parece alcanzable (un sondeo en vivo de `auth.login-url`), `/admin/settings` también
muestra una sección para la configuración de backend **propia** de auth.war: `auth.backend`
(`pam`/`smb`), servidor/dominio SMB, y los controles de grupo/recurso-compartido-requerido de
[§8](#8-auth-backend-de-inicio-de-sesión) — hoy estos solo viven en los parámetros de
contexto/propiedades de sistema del `web.xml` de auth.war, fijos en el momento del despliegue.

Guardar esta sección (junto con el nombre de cookie de arriba, con el que auth.war también necesita
estar de acuerdo — es el que realmente establece la cookie) los escribe en el archivo de
propiedades compartido en `nspawnmgr.auth.settings-file`. `AuthConfig` comprueba este archivo
**primero**, en cada solicitud, antes de sus propios parámetros de contexto/propiedades de
sistema — así que un guardado aquí tiene efecto en la siguiente solicitud de auth.war, sin
reiniciar ninguna de las dos aplicaciones web. Un valor en blanco/sin establecer aquí solo
significa "sin anulación"; auth.war recurre a su propio predeterminado de `web.xml`/propiedad de
sistema exactamente como antes de que esto existiera. La escritura del archivo es de mejor
esfuerzo: si falla (p. ej. una instalación manual se saltó la configuración de
`/etc/nspawnmgr/auth-live/` en [§5](#5-instalación-de-nspawnmgr)), el guardado en la base de
datos de todos modos tiene éxito y se registra una advertencia — no bloquea el resto de la
actualización de ajustes.

#### Sección de Guacamole (condicional)

Si Guacamole parece alcanzable (un sondeo en vivo de `guacamole.base-url`), `/admin/settings`
también muestra un editor estructurado para `guacamole.properties` (en
`nspawnmgr.guacamole.home`): campos individuales para `guacd-hostname`/`guacd-port`/`guacd-ssl`,
más un selector de tipo de base de datos (MySQL/MariaDB o PostgreSQL) que revela cada campo que
admite la extensión `guacamole-auth-jdbc` correspondiente — conexión, SSL/TLS, política de
contraseñas, límites de concurrencia por conexión, integración de autenticación externa, y
aplicación de ventanas de acceso. Las etiquetas de campo y el texto de ayuda provienen directamente
del [manual de Apache Guacamole](https://guacamole.apache.org/doc/gug/configuring-guacamole.html)
(páginas de la extensión de autenticación de
[MySQL](https://guacamole.apache.org/doc/gug/mysql-auth.html) /
[PostgreSQL](https://guacamole.apache.org/doc/gug/postgresql-auth.html)), no inventados
localmente.

Cargar la página lee el archivo existente y rellena previamente cada campo, incluyendo cualquier
contraseña ya establecida (renderizada en un `<input type="password">` enmascarado estándar, igual
que al cambiar una credencial guardada en cualquier otro sitio de esta aplicación — no visible en
texto plano en pantalla, pero note que esto es una elección de diseño deliberada: a diferencia del
resto de `/admin/settings`, que mantiene los secretos completamente fuera de la superficie de
edición en vivo, todo el propósito de este editor es dejar que un administrador vea y ajuste una
configuración de BD de Guacamole existente sin conectarse por SSH). Guardar solo toca las claves
documentadas arriba: borra las claves de la extensión de base de datos que *no* seleccionó (para
que el archivo no acumule configuración obsoleta de una elección anterior) y preserva sin tocar
cualquier otra clave ya en el archivo (p. ej. los propios ajustes de una extensión añadida a mano).
Guardar **no** reinicia Tomcat — Guacamole no verá el cambio hasta que lo haga usted
(`sudo systemctl restart tomcat9`).

#### Informe de ajustes

"Descargar informe de ajustes" produce un archivo de texto plano con todos los ajustes de la
página (más el `DB_URL`/`DB_USERNAME`/`DB_VENDOR` persistido del asistente de base de datos y los
valores de archivo actuales del editor estructurado de Guacamole), agrupados de la misma manera
que la propia página. Cada valor con forma de contraseña — `ssh.password`, `DB_PASSWORD`,
cualquier clave `*-password` de Guacamole — se reemplaza con un `********` literal: el informe
confirma *que* un valor está establecido, nunca cuál es.

#### Reiniciar Tomcat

Dispara `sudo systemctl restart --no-block tomcat9` a través de la misma cuenta SSH con capacidad
sudo y la concesión de sudoers NOPASSWD que ya usa cualquier otra operación privilegiada rutinaria
(vea [§3](#3-la-cuenta-ssh-con-capacidad-sudo)) — el `.deb` distribuye el script envoltorio
necesario (`/usr/lib/nspawnmgr/privileged/nspawnmgr-restart-tomcat.sh`) y la entrada de sudoers
automáticamente. Una instalación manual (no `.deb`) necesita añadir ambos a mano: copie el script
de `packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-restart-tomcat.sh` a
`nspawn.privileged-scripts-dir`, luego añada su ruta al alias `NSPAWNMGR_NOPASSWD` en
`/etc/sudoers.d/nspawnmgr_exec` (valide con `visudo -cf` antes de confiar en él).

El reinicio se dispara de forma asíncrona (`--no-block` encola el trabajo de systemd y regresa casi
instantáneamente) en lugar de esperarse — esperar no funcionaría de todos modos, ya que la propia
solicitud que pide el reinicio es servida por la instancia de Tomcat que está a punto de caer.
Después de hacer clic en el botón y confirmar, la página espera 5 segundos, borra la cookie de
sesión del lado del cliente, y recarga — aterrizando de vuelta en la página de inicio de sesión una
vez que la aplicación (para entonces ya reiniciada) ve la cookie ausente, de la misma manera en que
lo haría para cualquier otra sesión expirada.

## 10. Verificación del despliegue

**En una instalación `.deb`** (autoalojada — [§1](#1-resumen-de-la-arquitectura)):
`<hostname>:<puerto>` de abajo significa el puerto que imprimió la instalación durante
`postinst` (8080 a menos que ya estuviera en uso), y los comandos de `machinectl list`/
comprobación de logs necesitan `sudo machinectl shell nspawnmgr <comando>` — Tomcat, `guacd`, y
los logs de ambos WAR viven todos dentro de esa máquina, no en el host. En una instalación manual,
opción B (Tomcat en el host), todo lo de abajo se ejecuta directamente en el host en su lugar,
igual que siempre lo ha hecho.

1. Confirme que la máquina autoalojada `nspawnmgr` está activa: `sudo machinectl list` en el host
   debería mostrarla `running` (y, una vez que haya pasado por §4, también su máquina de base de
   datos). Dentro de ella, tanto `guacd` como Tomcat (`nspawnmgr.war` + `guacamole.war` +
   `auth.war`) deberían estar ambos en ejecución.
2. Visite `http://<hostname>:<puerto>/auth/login` directamente y confirme que puede iniciar sesión
   con la cuenta inicial creada durante el asistente de §4 (y, si está configurado, que una cuenta
   fuera de `auth.required-group`/`smb.required-share` se rechaza correctamente).
3. Visite `http://<hostname>:<puerto>/nspawnmgr/` sin ninguna cookie presente — debería ser
   redirigido a la página de inicio de sesión de `auth` y, después de iniciar sesión, de vuelta a
   nspawnmgr. Las máquinas `nspawnmgr`/base de datos ya deberían aparecer como contenedores
   ordinarios en la lista de contenedores en este punto — el asistente las registra directamente,
   sin necesitar inicio de sesión primero.
4. Cree un contenedor nuevo a través de la interfaz de nspawnmgr y confirme que realmente arranca
   (`sudo machinectl list` en el host debería mostrarlo) y que aparece una conexión de Guacamole
   para él.
5. Compruebe la propia página "Ver log" de nspawnmgr (una vez que esté al menos lo bastante
   avanzado como para servir páginas), o `sudo machinectl shell nspawnmgr journalctl -u tomcat9`
   para fallos de nivel más bajo, si algo de lo de arriba falla — la mayoría de los problemas de
   primer despliegue son un desajuste de nombre de host/cookie (§8) o que la cuenta sudo (§3) no
   tenga realmente configurado correctamente el acceso sudo/SSH.

## 11. Operaciones del día 2

- **Logs**: `<directorio-tomcat>/logs/catalina.out.<fecha>.log` para la única instancia de Tomcat
  (nspawnmgr, Guacamole, y auth todos registran ahí); `journalctl -u guacd` para el propio daemon
  proxy de Guacamole — en una instalación `.deb` (autoalojada) ambos viven *dentro* de la máquina
  `nspawnmgr` (`sudo machinectl shell nspawnmgr <comando>`), no en el host. El `.deb` conecta la
  propia salida estándar/error estándar de Tomcat a través de `rotatelogs` (`apache2-utils`) vía el
  `ExecStart` de `tomcat9.service`, produciendo un archivo con fecha nuevo diariamente — a
  diferencia de un simple `catalina.sh start`, el `tomcat9.service` de este paquete ejecuta
  `catalina.sh run` directamente, lo cual nunca produce un `catalina.out` sin fecha por sí solo
  (eso es solo lo que vería ejecutando Tomcat de forma interactiva, p. ej. la pila de desarrollo).
  Todo usuario que haya iniciado sesión puede ver las últimas 100 líneas y el log actual completo
  en la propia página "Ver log" de nspawnmgr; los administradores también pueden navegar y eliminar
  días individuales rotados desde ahí.
- **Reiniciar**: reinicie Tomcat después de cambiar cualquier configuración de `-D`/variable de
  entorno — nada de eso se recarga en caliente, y dado que las tres aplicaciones web comparten una
  instancia, reiniciarla las reinicia a las tres juntas. Reinicie solo `guacd` después de cambiar
  `guacd-hostname`/`guacd-port` en `guacamole.properties`.
- **Copias de seguridad**: haga copia de seguridad de la propia base de datos de nspawnmgr
  (metadatos de contenedor/usuario), la propia base de datos de Guacamole (historial/parámetros de
  conexión), y `/var/lib/machines` (sistemas de archivos raíz de los contenedores) por separado —
  son almacenes independientes sin ninguna integridad referencial cruzada impuesta más allá de lo
  que gestiona nspawnmgr a nivel de aplicación.
- **Rotar `APP_SECRET_KEY`**: no hay ninguna herramienta de recifrado incorporada; trate esto como
  una operación de romper-el-cristal, planificada de antemano, no algo que cambiar casualmente en
  un sistema en producción.
- **Solicitudes de contenedor pendientes** (solo modo de aprobación del administrador): aparecen en
  `/requests`. `DENIED` actualmente es un estado terminal — no hay ninguna posibilidad de volver a
  enviar, el usuario solicitante tiene que crear un contenedor nuevo desde cero.
