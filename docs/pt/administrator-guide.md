# Guia do Administrador do nspawnmgr

Este guia percorre a configuração de uma implantação real, em produção, do nspawnmgr a partir do
zero: o host Linux e o `systemd-nspawn`, o banco de dados, o Tomcat, o Apache Guacamole, o
aplicativo de login `auth` e o próprio nspawnmgr. Ele parte do princípio de um único host Linux da
família Debian/Ubuntu executando tudo, que é o arranjo contra o qual o próprio projeto é construído
e testado; adapte caminhos/nomes de pacotes se estiver usando uma distribuição diferente.

Para o ciclo de desenvolvimento local (simulações, sem contêineres reais, sem Guacamole real), veja
`site/env/README.md` e `dev_env/README.md` em vez disso — este guia trata de uma implantação real.

## 1. Visão geral da arquitetura

**O nspawnmgr é executado a partir de uma de suas próprias máquinas systemd-nspawn** — um contêiner
Debian autoalojado chamado `nspawnmgr`, criado automaticamente pelo `postinst` do `.deb`
(`nspawnmgr-bootstrap-app-machine.sh`) antes mesmo de qualquer administrador tocar no aplicativo.
Apenas um pequeno conjunto fixo de coisas permanece no host puro:

| Permanece no host | Por quê |
|---|---|
| `nspawnmgr_exec` (a conta SSH com capacidade de sudo, [§3](#3-a-conta-ssh-com-capacidade-de-sudo)) | A criação/gestão de contêineres precisa de root real no host puro — esta é a única conta que o possui |
| Templates e pacotes (`/var/lib/nspawnmgr/templates`, o cache de pacotes do administrador) | Armazenamento compartilhado, do lado do host, a partir do qual todo contêiner (incluindo o próprio do nspawnmgr) é construído |
| `nspawnbr0` (a ponte compartilhada) e o dnsmasq | Rede à qual todo contêiner, incluindo os autoalojados, se conecta |

Tudo o mais — o Tomcat, os quatro WARs (`nspawnmgr.war`, `auth.war`, `guacamole.war`, `ROOT.war`) e
o `guacd` — é executado **dentro** da máquina `nspawnmgr`, todos em uma única instância do Tomcat 9
ali, cada um em seu próprio caminho de contexto (`/nspawnmgr`, `/auth`, `/guacamole`, e `/` para
`ROOT.war`) exatamente como antes — apenas *onde* essa instância do Tomcat é executada mudou, não
como os quatro WARs estão organizados entre si. Veja o comentário no topo do `pom.xml` raiz para
saber por que o próprio nspawnmgr está fixado no Boot 2.7/Tomcat 9 (para corresponder ao próprio
webapp do Guacamole, que não pode rodar em Jakarta EE/Tomcat 10+ sem modificações) e o comentário no
topo de `auth/pom.xml` para o mesmo raciocínio aplicado ao `auth`.

Como a máquina `nspawnmgr` não tem acesso à rede do host (apenas um veth comum na `nspawnbr0`, como
qualquer outro contêiner), o `postinst` também escolhe uma porta livre do host (`8080`, ou a próxima
livre — ele imprime qual) e a encaminha diretamente para a porta `:8080` dessa máquina via uma linha
`Port=` em seu arquivo `.nspawn`, o mesmo mecanismo que os [mapeamentos de porta
personalizados](#mapeamentos-de-porta-personalizados-e-acesso-de-saída) usam para contêineres comuns.
Navegar até `http://<este host>:<essa porta>/` continua, portanto, alcançando o nspawnmgr exatamente
como sempre alcançou — o autoalojamento é invisível do lado do navegador.

O backend PAM do `auth.war` (o padrão — veja [§8](#8-auth-backend-de-login)) autentica contra
quaisquer contas de sistema operacional locais em que a própria JVM esteja rodando. Como o
`auth.war` agora é executado dentro da máquina `nspawnmgr`, isso significa que suas próprias contas
— criadas durante o [assistente de configuração de primeira
inicialização](#assistente-de-configuração-de-primeira-inicialização), não as do host puro — sem
que nenhum código ou configuração de backend seja necessário para tornar isso verdade.

O banco de dados também é autoalojado: o assistente de configuração de primeira inicialização
provisiona sua própria máquina de banco de dados Debian (veja [§4](#4-banco-de-dados)) em vez de se
conectar a um servidor já existente. Tanto a máquina `nspawnmgr` quanto sua máquina de banco de
dados aparecem como contêineres comuns e visíveis na própria lista de contêineres do nspawnmgr assim
que o assistente de primeira inicialização termina — veja a observação do [§4](#4-banco-de-dados)
sobre isso. Ambas também estão configuradas para [iniciar automaticamente quando o próprio host
inicializa](#iniciando-automaticamente-quando-o-host-inicializa), com o `nspawnmgr` configurado para
exigir que sua máquina de banco de dados já esteja iniciada — caso contrário, uma reinicialização do
host poderia iniciar a máquina `nspawnmgr` antes mesmo de sua máquina de banco de dados estar de pé,
deixando-a rodando sem um banco de dados alcançável até que alguém percebesse e iniciasse a outra
máquina manualmente.

O próprio nspawnmgr nunca executa `machinectl`/`systemd-run` diretamente — a conta sob a qual o
Tomcat roda não tem sudo, onde quer que o próprio Tomcat esteja rodando. Em vez disso, o nspawnmgr
conecta via SSH na **conta separada, com capacidade de sudo, `nspawnmgr_exec` no host puro** e
executa comandos privilegiados como root lá — operações rotineiras (iniciar/parar/excluir um
contêiner, sincronização de firewall) sem nunca precisar de uma senha, e apenas as mais arriscadas,
apenas no momento da criação (que executam conteúdo escrito em um template como root dentro de um
contêiner novo, ou provisionam uma máquina inteiramente nova) exigindo uma, obtida a partir da
configuração armazenada ou de uma aprovação de administrador por solicitação. Em uma instalação
empacotada, essa conexão SSH tem como alvo o próprio endereço fixo da `nspawnbr0` (`10.100.0.1`) em
vez de `127.0.0.1`, já que o nspawnmgr está alcançando o host *de fora* de sua própria máquina, em
vez de falar consigo mesmo — configurado automaticamente pelo `nspawnmgr-bootstrap-app-machine.sh`,
nada para configurar manualmente. Configurar essa conta é um dos passos mais importantes e fáceis de
esquecer abaixo ([§3](#3-a-conta-ssh-com-capacidade-de-sudo)).

## 2. Pré-requisitos do host

No host Linux que executará os contêineres:

```bash
sudo apt update
sudo apt install -y systemd-container openssh-server
```

`systemd-container` fornece `machinectl`, `systemd-nspawn` e `systemd-run` — incluindo
`machinectl import-tar`, que o nspawnmgr usa para clonar um template de contêiner em uma máquina
nova (conversa com o `systemd-importd`, ativado por socket da mesma forma que o `systemd-machined`
é para `machinectl start`, então deve simplesmente funcionar sem nenhuma configuração separada).
Confirme se o básico funciona:

```bash
machinectl list-images   # should run without error, even with an empty list
```

O nspawnmgr espera que dois diretórios existam e sejam graváveis pela conta com capacidade de sudo
(criados automaticamente pelo `systemd-nspawn`/`machinectl` na primeira vez que são usados, mas vale
confirmar):

- `/var/lib/machines` — onde vivem os sistemas de arquivos raiz dos contêineres
  (`NSPAWN_MACHINES_DIR`)
- `/etc/systemd/nspawn` — onde vivem os arquivos de configuração `.nspawn` por contêiner
  (`NSPAWN_SETTINGS_DIR`)

Esses são **caminhos de sistema reais e fixos** — `machinectl`/`systemd-nspawn` nunca procuram em
outro lugar, independentemente do que a própria configuração do nspawnmgr diga. Não tente
colocá-los em sandbox.

### Bancos de dados (dois, separados — um para o nspawnmgr e outro para o Guacamole)

Planeje para **dois bancos de dados independentes**, ambos no mesmo servidor MySQL/MariaDB ou
PostgreSQL: o próprio esquema de usuários/contêineres/configurações/templates do nspawnmgr, e o
próprio esquema de usuários/conexões/permissões do Guacamole (gerenciado separadamente pela extensão
`guacamole-auth-jdbc` do Guacamole). **Apenas MySQL/MariaDB ou PostgreSQL — sem opção H2.** Veja
[§4](#4-banco-de-dados) — o assistente de configuração de primeira inicialização cria os dois bancos
de dados para você, com nomes fixos e opinativos (`nspawnmgr`/`guacamole`), então não há nada para
preparar manualmente com antecedência.

### Templates de contêiner (sistemas de arquivos raiz base)

O nspawnmgr provisiona contêineres novos clonando um "template" em `/var/lib/machines` via
`machinectl import-tar`. Os próprios templates vivem sob `TEMPLATES_DIR` (padrão
`/var/lib/nspawnmgr/templates`), um subdiretório por backend — `nspawn/`, `podman/` e `qemu/` (veja
["Podman: pods"](#podman-pods) e ["QEMU: máquinas virtuais"](#qemu-máquinas-virtuais) abaixo para os
formatos de template próprios dos outros dois backends e como cada um é populado — esta seção trata
especificamente dos arquivos `<nome>.tar.gz` do nspawn: tars gzipados simples de um sistema de
arquivos raiz, exatamente o que o próprio `machinectl import-tar` consome). Você precisa preparar
pelo menos um real, inicializável, você mesmo — o nspawnmgr não baixa ou constrói esses para você,
com uma exceção: `/admin/templates` oferece três botões independentes **"Configurar X-minimal"** —
**debian-minimal** (APT), **fedora-minimal** (DNF), **arch-minimal** (PACMAN) — cada um mostrado
apenas enquanto o template específico daquele sabor ainda não existir (configurar um não esconde os
outros; configure um, alguns ou todos os três). Cada um baixa um minirootfs real (verificado por
checksum) de images.linuxcontainers.org, instala e habilita um servidor SSH nele, empacota-o como
`TEMPLATES_DIR/nspawn/<sabor>-minimal.tar.gz`, e o registra com sua flag "SSH pré-instalado"
ativada — um template real e funcional em um clique. Essa flag (também configurável em qualquer
template criado manualmente, veja seu formulário de edição) informa à criação de contêiner que a
imagem já tem SSH instalado e habilitado, pulando a etapa de download/instalação/habilitação
normalmente redundante que todo outro template exige. Não é uma ferramenta geral de gestão de
templates: não há botão equivalente para um nome personalizado, e cada botão desaparece assim que o
template do seu sabor específico existir (independentemente de quais outros templates existam).
Mesma exigência de sudo que tudo o mais que é apenas-no-momento-da-criação (§3) — no modo de
aprovação de administrador, você será solicitado a inserir a senha de sudo diretamente na tela. Veja
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-{debian,fedora,arch}-template.sh` para
saber exatamente o que cada um faz — **apenas o do Debian foi confirmado contra um contêiner real**;
veja ["Templates Fedora e Arch: status de
verificação"](#templates-fedora-e-arch-status-de-verificação) abaixo para o status de verificação
dos outros dois, e para a abordagem de caminho duplo (nativo do host vs. chroot) que os três scripts
agora compartilham. O próprio
`site/templates/nspawn/{debian-minimal,fedora-minimal,arch-minimal,alpine-minimal}` do repositório é
uma coisa *diferente* — diretórios de placeholder minúsculos (nem sequer tarballs) usados apenas
para testes locais em modo de desenvolvimento (veja `site/templates/README.md`) — **não os use como
templates reais**, eles não são inicializáveis.

Deliberadamente, não há sabor Alpine entre os três: o minirootfs oficial do Alpine não tem
systemd/D-Bus algum (usa OpenRC), e todo comando dentro do contêiner que o nspawnmgr executa passa
por `systemd-run --machine=`, que exige que o próprio contêiner esteja rodando systemd — um
contêiner baseado em Alpine falha com "Failed to connect to bus" permanentemente, não como uma
disputa transitória de inicialização que vale a pena tentar novamente. Um suporte real ao Alpine
precisaria do systemd instalado e funcionando como PID 1 dentro do contêiner primeiro, o que é fora
do padrão no Alpine e não testado aqui.

#### Templates Fedora e Arch: status de verificação

**debian-minimal é o único dos três botões "Configurar X-minimal" confirmado contra um contêiner
real** — foi criado e inicializado ao vivo várias vezes ao longo deste projeto. **fedora-minimal** e
**arch-minimal** permanecem especificamente não verificados: hosts Fedora/Arch reais existem e têm
sido usados extensivamente em outras partes deste projeto (veja as seções de instalação de pacotes
RPM/Arch acima), mas `nspawnmgr-create-fedora-template.sh`/`nspawnmgr-create-arch-template.sh` — os
scripts que esses dois botões específicos da interface de administração chamam — nunca foram
realmente exercitados contra um contêiner systemd-nspawn real. Se você tentar qualquer um deles, por
favor, relate o que quebra — algumas áreas de risco conhecidas específicas, aproximadamente em ordem
de quão provável é que causem problemas:

- **Os três scripts de preparação (Debian, Fedora, Arch) detectam a própria distro do HOST e
  escolhem um de dois caminhos de instalação de acordo**, em vez de assumir qualquer distro
  específica. Cada script verifica `command -v apt-get`/`dnf`/`pacman` para seu PRÓPRIO gerenciador
  de pacotes alvo: se o host tiver um correspondente, ele executa essa ferramenta como um
  **processo normal do lado do host** apontado para o rootfs extraído (a combinação `-o Dir=`/`-o
  DPkg::Options::=--root=` do apt, `dnf --installroot=`, `pacman --root=`). Se o host não tiver
  gerenciador de pacotes correspondente algum (ex.: nspawnmgr implantado em um host Debian
  preparando um template Fedora ou Arch, ou vice-versa), o script em vez disso **faz `chroot` no
  rootfs recém-extraído e usa a própria cópia embutida da ferramenta da imagem** — `/etc/resolv.conf`
  copiado para dentro (o chroot não compartilha a configuração de rede do host), `/dev`/`/proc`/
  `/sys`/`/run` montados via bind antes que a instalação via chroot seja executada (o bind mount de
  `/run` especificamente torna o módulo NSS do `systemd-resolved` alcançável para resolução de DNS
  dentro do chroot — sem ele, a resolução de nomes pode falhar mesmo com um `/etc/resolv.conf`
  correto no lugar), desmontados novamente imediatamente depois, antes que o tarball seja empacotado
  — a mesma técnica que o próprio estágio de chroot do `pacstrap`/`arch-chroot`/`debootstrap` usa.
  Apenas o ramo do lado do host do script Debian (Debian-em-Debian) foi de fato exercitado contra um
  contêiner real; o fallback de chroot do script Debian, e ambos os ramos dos scripts Fedora/Arch,
  foram construídos conforme especificação mas não verificados — esses scripts específicos de
  preparação de template de contêiner nunca foram executados de verdade, mesmo que hosts Fedora/Arch
  reais existam e sejam usados em outras partes deste projeto.
- **arch-minimal é o mais especulativo dos três.** Áreas de risco conhecidas: (1) o
  `/etc/pacman.d/mirrorlist` da imagem baixada vem com todo espelho comentado pela própria convenção
  do Arch — o script escreve `geo.mirror.pkgbuild.com` (o redirecionador GeoIP oficial do Arch)
  explicitamente; (2) a verificação de assinatura de pacote precisa de um chaveiro populado que este
  script não configura (o `pacstrap` real faz isso, via `pacman-key --init`/`--populate`) — em vez de
  tentar isso às cegas sem forma de testar, o script desabilita a verificação de assinatura
  (`SigLevel = Never` no `pacman.conf` do alvo) para esta instalação de bootstrap, uma troca real de
  segurança que vale a pena conhecer, mesmo sendo uma razoável para um template rápido de
  desenvolvimento/teste; (3) o ramo de chroot também desabilita `CheckSpace` em `pacman.conf` — a
  verificação de espaço em disco do pacman resolve o diretório de cache para um ponto de montagem via
  `/proc/self/mountinfo`, que dentro de um chroot ainda reflete os próprios caminhos absolutos do
  host em vez do `/` remapeado do chroot, então a verificação falha com um enganoso "espaço em disco
  insuficiente" independentemente do espaço real disponível (uma limitação conhecida do
  pacman-em-chroot); (4) `pacman.conf` também recebe `DisableSandbox` adicionado — o sandboxing de
  download baseado em Landlock do próprio pacman (mais um usuário `alpm` sem privilégios dedicado
  para o qual ele muda) é bloqueado pelo filtro seccomp padrão do `systemd-nspawn` assim que um
  contêiner de fato inicializa e executa `pacman` ao vivo (ao contrário do próprio `chroot` do lado
  do host deste script, que não tem restrições de seccomp alguma) — toda invocação de `pacman` dentro
  de um contêiner real e em execução precisa disso para funcionar, não apenas a etapa de preparação
  deste próprio script.
- **RDP está indisponível para o `arch-minimal` inteiramente.** Confirmado ao vivo: `xrdp`/`xorgxrdp`
  foram removidos dos repositórios oficiais do Arch (`pacman -Ss xrdp` não encontra nenhum dos dois,
  em um espelho recém-sincronizado e totalmente populado — não é um problema de cache obsoleto ou
  espelho errado) e este aplicativo não tem suporte a AUR para recorrer. `arch-minimal` define seu
  próprio estado de RDP como "não capaz" por padrão (veja o seletor "RDP" da página de administração
  de Templates), que é o que de fato desabilita a opção "Habilitar RDP" no formulário New Nspawn para
  ele — reative manualmente apenas se um futuro lançamento do Arch restaurar o pacote, ou se o
  comando de instalação do próprio template for editado manualmente para algo que funcione (ex.: o
  próprio `krdp` do KDE, ainda em `extra`, mas atrelado especificamente a KDE/Plasma).
- **Todo contêiner Fedora precisa ter sua verificação PAM de fase de conta do `sshd` contornada para
  ser alcançável via SSH.** Toda tentativa de login SSH por chave pública em um contêiner Fedora
  real e inicializado (confirmado tanto no 43 quanto no 44 — não é específico de release) é rejeitada
  com `Access denied for user <account> by PAM account configuration [preauth]` (a fase de conta do
  `pam_unix`, `pam_acct_mgmt`, retorna `PAM_AUTHINFO_UNAVAIL`) — a conta, sua senha, e seu
  `authorized_keys` estão todos genuinamente corretos; o próprio `unix_chkpwd` (o helper setuid que o
  `pam_unix` invoca, para ler `/etc/shadow` com segurança) se recusa a rodar com "This binary is not
  designed for running in this way" — alguma verificação de legitimidade do chamador no
  `shadow-utils` atual do Fedora que não tolera rodar dentro de um contêiner `systemd-nspawn`.
  `UsePAM no` em `sshd_config` **não** contorna isso — confirmado ao vivo, o próprio processo monitor
  privilegiado do sshd ainda chama `do_pam_account` de qualquer forma nesta build (o próprio sshd
  avisa `'UsePAM no' is not supported in this build`). A correção que funciona: o script aponta a
  fase de conta do `sshd` para `pam_permit.so` (sempre bem-sucedida) em vez do `pam_unix.so` do
  `password-auth`, apenas em `/etc/pam.d/sshd` — não é uma mudança de PAM em todo o sistema. Isso
  remove as verificações de fase de *conta* do PAM (expiração, `nologin`, etc.) especificamente para
  SSH; a verificação real de identidade (verificação de chave pública) já é bem-sucedida
  independentemente, antes que essa fase sequer rode, então esta é uma troca estreita e deliberada
  para essas contas de administrador provisionadas descartáveis. Confirmado funcionando ao vivo no
  Fedora 43; a release permanece fixada em 43 (não a mais nova 44) simplesmente porque essa é a
  combinação exata verificada de ponta a ponta, não porque a 44 seja de alguma forma pior.
- **O prompt de SSH de todo contêiner Fedora e Arch estava cheio de texto literal de sequência de
  escape** — `start=<uuid>;machineid=<uuid>;user=...;hostname=...;bootid=<uuid>;pid=...;type=shell;
  cwd=...` em vez de um simples `[user@host ~]$`. Causa raiz (confirmada ao vivo no Fedora; o Arch
  mostrou o mesmo sintoma e compartilha a mesma causa raiz, já que não é uma peculiaridade específica
  do Fedora — apenas qualquer distro cujo systemd seja novo o suficiente para enviá-la, ambas estão
  aqui): systemd 257+ inclui `/usr/lib/systemd/profile.d/80-systemd-osc-context.sh` (vinculado
  simbolicamente em `/etc/profile.d/` pelo `systemd-tmpfiles`), que emite uma sequência de escape OSC
  3008 "Hierarchical Context Signalling" a cada prompt; o próprio emulador de terminal do Guacamole
  não a reconhece/remove, então ela é impressa como texto literal. O script só se pula quando `$TERM`
  não está definido ou é `dumb` (veja seu próprio comentário de cabeçalho), e o cliente SSH do
  Guacamole informa um `$TERM` real, então ele sempre dispara. Desabilitado da forma documentada (o
  próprio comentário de cabeçalho do script fornece este procedimento exato) em ambos os scripts de
  preparação: remover o link simbólico de `/etc/profile.d/` e mascarar o trecho de `tmpfiles.d` que
  o recria.
- **Instalar o gerenciador de desktop Xfce em um contêiner Fedora falhava completamente** —
  `dnf group install -y "Xfce Desktop"` retornava erro com `No match for argument: Xfce Desktop`.
  Confirmado ao vivo: diferente de GNOME/KDE, "Xfce Desktop" não é um grupo comps no Fedora atual de
  forma alguma (`dnf group list --available` não o lista) — o Fedora em vez disso disponibiliza um
  pacote simples e nomeado, `xfce4`, que traz todo o desktop. Trocado por um simples
  `dnf install -y xfce4`, o que também torna o Xfce-no-DNF pré-buscável primeiro (veja "Instalação de
  pacotes: baixados primeiro" acima) — diferente das instalações via grupo comps do GNOME/KDE, que
  ainda não podem ser pré-buscadas e ainda precisam da própria rede/DNS do contêiner para funcionar.
  De quebra, esse mesmo mecanismo de pré-busca foi ampliado de apenas-APT para APT/DNF/PACMAN de
  forma geral (os scripts de download subjacentes já suportavam os três; apenas a porta de decisão
  sobre usá-los ou não ainda era apenas-APT) — os nomes de pacotes SSH/RDP/VNC agora também são
  resolvidos por gerenciador de pacotes (ex.: o pacote SSH do Arch é `openssh`, não
  `openssh-server`; sua instalação de RDP adicionalmente precisa de `xorgxrdp`).
- **Essa ampliação da pré-busca então quebrou completamente a criação de contêineres Fedora/Arch** —
  `Failed to download DNF packages [openssh-server] ... dnf: not found`, e a falha idêntica para
  PACMAN. Confirmado ao vivo em ambos. Causa raiz: `nspawnmgr-download-packages-dnf.sh`/
  `-pacman.sh` (e seus irmãos de simulação de instalação, usados pelo fluxo de upload de Pacotes da
  administração) executavam `dnf`/`pacman` diretamente no *host* (`--installroot=`/`--root=` apontado
  para o rootfs do contêiner) — funciona para APT, já que o `.deb` deste projeto só tem como alvo
  hosts Debian/Ubuntu, que sempre têm `apt-get`, mas nem `dnf` nem `pacman` jamais estão no `PATH`
  próprio de um host desses. Diferente da *preparação* de template (que pode recorrer a um `chroot`
  do lado do host em um rootfs ainda não inicializado), um contêiner ao vivo, já em execução, não
  pode ser colocado em chroot com segurança da mesma forma — a correção em vez disso executa
  `dnf`/`pacman` *dentro* do próprio contêiner via `systemd-run --machine=`, a mesma primitiva de
  execução não interativa dentro do contêiner que a etapa de instalação real já usa, apenas download,
  então nenhum estado de pacote instalado muda. Compensação: a pré-busca DNF/PACMAN perde a
  reutilização entre-contêineres "um pacote já em cache nunca é buscado de novo" que o APT tem, já
  que o diretório de cache compartilhado do lado do host não é visível de dentro do próprio namespace
  de montagem de um contêiner — toda pré-busca DNF/PACMAN baixa tudo de novo.
- **A correção dentro do contêiner acima ainda falhou na primeira nova tentativa ao vivo** — o dnf5
  rejeita `--destdir` em `install` completamente (`Unknown argument "--destdir=..." for command
  "install" ... available for: reposync, download, upgrade`); a combinação `install --downloadonly
  --destdir=` do dnf4 não é transportada. O próprio comando do dnf5 para baixar sem instalar é
  `download`, e por padrão ele busca apenas o(s) pacote(s) *nomeado(s)*, não suas dependências —
  `--resolve` é o que traz todo o fechamento também, o real equivalente do dnf5 ao que
  `install --downloadonly` fornecia. Corrigido: `dnf download --resolve --destdir=<dir> <packages>`.
  Mesma lição dos bugs `groupinstall`→`group install`/EPEL-no-Fedora acima: a superfície de CLI do
  dnf5 difere da do dnf4 de formas reais e não óbvias — confirme ao vivo em vez de assumir que a
  sintaxe da era do dnf4 se transporta.
- Ambos os scripts também traduzem o nome de arquitetura do `uname -m` (`x86_64`/`aarch64`) para a
  própria convenção do images.linuxcontainers.org (`amd64`/`arm64`) antes de construir a URL —
  perder essa tradução resulta em 404 independentemente de a release/build estar correta.
- Ambos os scripts reutilizam os mesmos drop-ins de systemd-networkd para
  `net.ipv4.ping_group_range`/domínio DNS que o script Debian precisa — esses tratam da própria
  configuração de rede de contêiner gerada pelo systemd-nspawn, nada específico do Debian, então
  *deveriam* se transportar para qualquer rootfs baseado em systemd, mas isso é uma suposição, não um
  fato confirmado ao vivo, especificamente para Fedora/Arch.

O próprio pré-busca de dependência DNF do fluxo manual "Instalar pacote" (simular via
`dnf install --assumeno`, buscar via `dnf install --downloadonly`) carrega a mesma ressalva idêntica
de não-verificado-até-testado — veja "Fazendo upload e instalando pacotes arbitrários" acima.

Alternativamente, construa um template Debian manualmente via `debootstrap` (a mesma ideia de busca
de rootfs, se preferir não baixar de images.linuxcontainers.org, ou quiser uma release/arquitetura
diferente) — prepare em um diretório temporário, depois empacote-o no local real de `TEMPLATES_DIR`
como um tar gzipado:

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

Cada arquivo `.tar.gz` sob `TEMPLATES_DIR/nspawn/` é um template selecionável; registre/edite a
linha `Template` correspondente em `/admin/templates` (apenas administrador) — nome, identificador
de origem (o nome simples do arquivo, sem `.tar.gz`, sem prefixo de pasta de backend — ex.:
`debian-minimal` para `TEMPLATES_DIR/nspawn/debian-minimal.tar.gz`), backend, gerenciador de
pacotes, e substituições opcionais de comando de instalação. Todo template tem um **backend**
(`domain/ContainerBackend.java`: `SYSTEMD_NSPAWN`, `PODMAN`, ou `QEMU`) registrado contra ele, cada
um com seu próprio subdiretório e formato de arquivo em `TEMPLATES_DIR` — veja as seções abaixo para
os do Podman e do QEMU. Uma instalação nova começa com **zero** templates — nada é semeado — então
esta página (ou o botão "Configurar debian-minimal" abaixo) é genuinamente como você obtém o
primeiro; o próprio tarball sob `TEMPLATES_DIR` ainda precisa ser preparado fora de banda como acima
de qualquer forma, a página apenas gerencia os metadados que apontam para ele. Desativar um template
(em vez de excluí-lo) é a forma normal de aposentá-lo — ele desaparece do menu suspenso de criação de
contêiner, mas contêineres existentes construídos a partir dele não são afetados; excluir só é
permitido depois que nenhum contêiner o referenciar. Veja a seção "Fronteira de confiança" do
[§3](#3-a-conta-ssh-com-capacidade-de-sudo) para o que o controle de acesso apenas-para-administrador
desta página está de fato protegendo.

**Templates também podem ser criados a partir de uma máquina já existente**, não apenas baixados do
zero: a página de detalhes de um contêiner parado tem um campo "Criar template a partir desta
máquina" (nome + descrição opcional). Ele empacota o rootfs atual dessa máquina (`tar -czf`, a mesma
convenção que todo script de preparação acima já produz) em um template novo e independente — útil
para tirar um snapshot de um contêiner que um proprietário já personalizou, em vez de reprovisionar
do zero. Deliberadamente oferecido apenas enquanto a máquina está **PARADA**: empacotar um rootfs ao
vivo arrisca um arquivo inconsistente conforme os arquivos mudam no meio do processo de tar.
Diferente da página "Novo template"/"Configurar X-minimal", apenas para administradores, esta é uma
ação do proprietário do contêiner (`/api/containers/{id}/create-template`, não sob `/api/admin/**`)
— o template resultante é, de resto, idêntico, incluindo a mesma exigência de senha de sudo, e pode
depois ser usado por qualquer pessoa da mesma forma que qualquer outro template. Como o endpoint
"Instalar pacote", isso só funciona hoje no modo de segredo armazenado (sempre passa uma substituição
de senha de sudo nula) — o modo de aprovação de administrador ainda não está conectado a esta ação.

O campo de nome de origem do formulário "Novo template"/"Editar template" sugere os nomes simples de
todo `.tar.gz` já presente sob o subdiretório de backend selecionado desse template (obtido de
`GET /api/admin/templates/available-source-files?backend=...`, apoiado por
`nspawnmgr-list-template-files.sh` — um script wrapper NOPASSWD, somente leitura, como
`nspawnmgr-list-machine-images.sh`), para que você não precise se lembrar de um nome de arquivo
exato que preparou fora de banda. É um `<datalist>` de navegador, não um menu suspenso rigidamente
restrito — o campo ainda aceita texto livre, já que a lista de sugestões é de melhor esforço (vazia
se o host SSH estiver inalcançável ou o diretório não tiver nada ainda) e não deve bloquear o
registro dos metadados de um template antes mesmo do tarball realmente chegar ao disco.

**Mudança que quebra compatibilidade:** o armazenamento de templates mudou de uma árvore de
diretório extraída e ativa em `TEMPLATES_DIR/<nome>` (clonada via `cp -a`) para um tar gzipado em
`TEMPLATES_DIR/nspawn/<nome>.tar.gz` (clonado via `machinectl import-tar`). Uma linha `Template`
criada antes dessa mudança aponta para um local que o nspawnmgr não reconhece mais — exclua e
recrie-a (ex.: clique novamente em "Configurar debian-minimal") ou empacote manualmente qualquer
template personalizado colocado à mão no novo local/formato como mostrado acima.

#### Instalando/atualizando templates a partir de um pipeline de CI/CD

Para gestão de templates via script (um pipeline de CI/CD construindo e enviando seus próprios
templates) em vez de um humano clicando em `/admin/templates`, o nspawnmgr fornece uma CLI invocada
via SSH em vez de uma API web — este aplicativo não tem autenticação HTTP máquina-a-máquina alguma
(tanto a autenticação Basic quanto o login por formulário estão explicitamente desabilitados; o
único caminho de login é o cookie de sessão apoiado pelo seu serviço de identidade externo), então um
endpoint HTTP voltado para CI significaria inventar um novo mecanismo de autenticação do zero. A CLI
reutiliza o modelo de confiança SSH+sudo já existente deste projeto.

Isso usa uma segunda conta com capacidade de sudo, **deliberadamente isolada**, `nspawnmgr_ci` —
separada de `nspawnmgr_exec` (veja a seção "Fronteira de confiança" abaixo para saber por quê). Ela
não existe até que você opte por ativá-la:

```bash
sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
```

Isso cria a conta, trava o login por senha (autenticação apenas por chave), e imprime uma chave SSH
**privada** recém-gerada no stdout exatamente uma vez — copie-a imediatamente para o próprio
armazenamento de segredos do seu sistema de CI; nada é mantido no host além da metade pública.
Execute novamente com `--rotate-key` para substituí-la depois (a chave antiga para de funcionar
imediatamente, ela não permanece como uma segunda credencial válida).

A partir do seu pipeline de CI/CD, instale ou atualize um template (upsert, chaveado em `--name`)
transmitindo o tarball via SSH:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-template.sh \
  --name my-template --package-manager APT --description "Built by CI" \
  < my-template.tar.gz
```

`--name` se torna parte de um caminho de sistema de arquivos (`TEMPLATES_DIR/nspawn/<name>.tar.gz`)
e é validado de acordo (letras, dígitos, `-`, `_` apenas). `--package-manager` é obrigatório (`APT`,
`DNF`, `APK`, ou `PACMAN`); `--backend`, `--description`, `--install-ssh-command`,
`--install-xrdp-command`, `--rdp-capable`, `--active` são todos opcionais, correspondendo aos
próprios campos e padrões do formulário de administração. O tarball novo/atualizado só é trocado no
lugar depois que a linha do banco de dados é confirmada, então uma falha no meio do caminho nunca
deixa um template meio instalado — uma atualização em andamento deixa a versão anterior servindo
normalmente até que a nova esteja totalmente pronta.

#### Instalando/atualizando pacotes a partir de um pipeline de CI/CD

A mesma conta `nspawnmgr_ci` (sem etapa de ativação separada além da acima) também pode publicar
diretamente no [cache de pacotes de administração](#fazendo-upload-e-instalando-pacotes-arbitrários),
para um pipeline de CI que constrói seus próprios artefatos `.deb`/`.rpm`/etc. e quer disponibilizá-los
para que proprietários de contêiner os instalem sem um humano fazer upload manualmente:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-package.sh \
  --package-manager APT --filename my-tool_1.2.3_amd64.deb --description "Built by CI" \
  < my-tool_1.2.3_amd64.deb
```

`--package-manager` (`APT`/`DNF`/`APK`/`PACMAN`/`ISO` — veja [Mídia
removível](#mídia-removível-imagens-iso) para o que `ISO` significa aqui) e `--filename` são
obrigatórios (o último não pode conter `/` nem começar com `.`); `--description` é opcional.
Instalar-ou-atualizar (upsert) é chaveado em `--package-manager` + `--filename` juntos — executar
novamente com os mesmos dois substitui o arquivo anterior e atualiza sua linha no lugar, mesma
postura de segurança contra falhas que as instalações de template (a gravação no BD é confirmada
antes que o arquivo antigo no disco seja substituído). Como `cached_packages` exige uma conta real de
quem fez o upload (`uploaded_by_user_id`), o primeiro pacote instalado pelo CI provisiona
automaticamente um pseudo-usuário dedicado `nspawnmgr-ci` — mostrado como quem fez o upload na página
de administração e na seção "Instalar pacote" de todo contêiner, exatamente como o próprio nome de
usuário de um administrador humano apareceria.

### Reiniciando contêineres

A página de detalhes de um contêiner em execução tem um botão **Reiniciar** ao lado de Parar/Forçar
parada. Ele executa `machinectl reboot` — um reinício limpo, no lugar, do próprio sistema
operacional do contêiner, diferente de Parar+Iniciar: o registro da máquina e sua interface veth
nunca são desmontados e recriados, então mapeamentos de porta personalizados, o estado do firewall
de acesso de saída, e qualquer outra coisa vinculada a esse veth permanecem válidos sem precisar de
uma ressincronização. O contêiner passa pelo mesmo estado INICIANDO de uma partida do zero enquanto o
`ContainerReadinessPollingService` aguarda o SSH (e o RDP, se habilitado) voltarem a ficar de pé.

### Pausando e retomando contêineres

A página de detalhes de um contêiner em execução tem botões **Pausar**/**Retomar** ao lado de
Parar/Forçar parada. Diferente de Parar, nada é desmontado: Pausar executa `systemctl freeze`
contra a própria unidade `systemd-nspawn@<nome>.service` do contêiner, suspendendo todo processo em
seu cgroup no lugar via o freezer de cgroup do kernel (systemd 246+); Retomar executa
`systemctl thaw` para reverter isso, retomando exatamente de onde parou. O próprio `machinectl` não
tem conceito nativo algum de pausa/retomada — este é o equivalente moderno e nativo do systemd, o
mesmo mecanismo que `systemctl freeze`/`thaw` já fornecem para qualquer outro tipo de unidade.

Um contêiner iniciado via `machinectl start` (que é como o nspawnmgr sempre os inicia) roda como a
própria unidade `systemd-nspawn@<nome>.service` diretamente, sem um `machine-<nome>.scope` separado
— essa unidade de serviço é o que Pausar/Retomar têm como alvo. freeze/thaw funcionam contra
qualquer unidade com um cgroup, unidades de serviço incluídas. O próprio *comportamento* de
freeze/thaw (se o controlador de freezer está disponível/habilitado, se os processos genuinamente
suspendem/retomam corretamente) ainda vale a pena confirmar empiricamente se você depende muito
disso.

### Iniciando automaticamente quando o host inicializa

A página de detalhes de um contêiner GERENCIADO (não mostrada para hosts EXTERNOS, que não têm
imagem `machinectl` própria para habilitar) tem um painel **Configurações da máquina** com dois
campos:

- **Iniciar automaticamente quando o host inicializa** — uma caixa de seleção apoiada por
  `systemctl is-enabled`/`enable`/`disable` na própria unidade `systemd-nspawn@<nome>.service` do
  contêiner.
- **Exige que esta máquina já esteja iniciada** — um menu suspenso com o nome de todo outro
  contêiner GERENCIADO, apoiado por um drop-in de unidade systemd em
  `/etc/systemd/system/systemd-nspawn@<nome>.service.d/nspawnmgr-requires.conf`
  (`Requires=`/`After=` contra a unidade própria da máquina escolhida, com `systemctl daemon-reload`
  a cada mudança). Só faz sentido junto com o início automático acima — ele controla a *ordem* de
  inicialização entre duas máquinas que sobem sozinhas, não uma dependência em tempo de execução que
  Parar/Iniciar aplicaria de outra forma.

Ambos os campos são **lidos ao vivo do host a cada carregamento de página, não armazenados no
próprio banco de dados do nspawnmgr** — deliberadamente, já que nada impede um administrador de
executar `systemctl enable`/`disable` diretamente no host fora do nspawnmgr, e um valor em cache
poderia silenciosamente divergir do que o `systemd` realmente tem configurado. Uma falha transitória
de SSH ao lê-los mostra uma mensagem alternativa na página em vez de falhar completamente; salvar
uma mudança passa pelos mesmos dois scripts wrapper que a leitura
(`nspawnmgr-set-machine-autostart.sh`/`nspawnmgr-set-machine-requires.sh`, ambos NOPASSWD —
rotineiros, acionados pelo proprietário, mesma camada que Iniciar/Parar).

**A máquina autoalojada `nspawnmgr` e sua máquina de banco de dados** (veja
[§1](#1-visão-geral-da-arquitetura)) estão ambas configuradas para iniciar automaticamente dessa
forma, com o `nspawnmgr` configurado para exigir sua máquina de banco de dados — caso contrário, uma
reinicialização do host poderia trazer o `nspawnmgr` de pé antes que seu próprio banco de dados
estivesse alcançável. Isso é conectado por
`ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()` (a mesma passagem de reconciliação
de infraestrutura autoalojada que também vincula ambas as máquinas ao template `debian-minimal`,
provisiona seu acesso SSH gerenciado, e define a descrição delas na lista de contêineres — veja
[§1](#1-visão-geral-da-arquitetura) e ["Descobrindo máquinas criadas fora do
nspawnmgr"](#descobrindo-máquinas-criadas-fora-do-nspawnmgr)), que roda em sua própria programação
recorrente de ~30s a partir do momento em que o próprio aplicativo Spring do nspawnmgr sobe — não
condicionado a nenhuma ação de administrador. Uma falha transitória (registrada em WARN, nunca
fatal) simplesmente é retomada na próxima passagem, sem ação de administrador necessária; a mesma
reconciliação também continua rodando como parte de um clique manual em **Descobrir máquinas**.

### Rede de contêineres

Todo contêiner gerenciado compartilha uma ponte, `nspawnbr0` (`Bridge=nspawnbr0` no arquivo
`.nspawn` gerado — `machinectl start` escraviza o próprio veth de cada contêiner nela
automaticamente ao iniciar), em vez de cada um obter um veth ponto-a-ponto isolado em sua própria
sub-rede privada. `nspawnbr0` e seu endereço (`10.100.0.1/24`, fixo e não configurável pelo
administrador — uma convenção interna, não um ponto real de personalização) são criados
incondicionalmente pelo próprio postinst do `.deb`
(`/etc/systemd/network/70-nspawnmgr-bridge.netdev`/`.network`), não algo que você configura
manualmente. **Diagnóstico de rede** tem uma verificação somente leitura confirmando que ela está
realmente de pé.

**SSH/RDP/VNC não precisam de encaminhamento de entrada algum.** Tanto o `guacd` do Guacamole quanto
a própria verificação de prontidão do nspawnmgr discam diretamente para o endereço veth interno de um
contêiner GERENCIADO (sua interface `host0`, resolvida ao vivo via `machinectl`/`nsenter` — veja
`nspawnmgr-get-internal-address.sh`), na porta real de sshd/xrdp/VNC do contêiner (22/3389/5900). Não
há encaminhamento de porta do host algum no meio para esses, o que contorna uma limitação de NAT em
hairpin no mesmo host confirmada em hardware real: o tráfego do próprio host de volta através de seu
próprio endereço DNAT'd/encaminhado para um contêiner frequentemente não sofre novo NAT
corretamente, mesmo que um cliente genuinamente externo alcançando esse mesmo endereço+porta funcione
bem. O endereço interno atribuído ao contêiner é registrado (em INFO) no momento em que ele alcança
EM EXECUÇÃO, e ressincronizado com a configuração de conexão do Guacamole a cada reinício
subsequente, caso o endereço mude.

### Acesso gráfico: RDP, VNC e gerenciadores de desktop

O formulário "New Nspawn" tem duas caixas de seleção independentes, **Habilitar RDP** e **Habilitar
VNC** — qualquer uma, ambas, ou nenhuma. Escolher qualquer uma revela um menu suspenso **Gerenciador
de desktop** (Nenhum/GNOME/KDE (`kde-standard`)/Xfce (`xfce4`)): um protocolo gráfico tem utilidade
limitada sem um ambiente de desktop de fato dentro de um template minimalista, então escolher um
instala-o durante o provisionamento, compartilhado entre RDP e VNC se ambos forem escolhidos.
**Nenhum** significa que nada extra é instalado.

Diferente do acesso por credenciais solicitadas coberto abaixo, RDP/VNC escolhidos no momento da
criação recebem uma conta/senha real gerada que o nspawnmgr cria e armazena (RDP reutiliza a conta
SSH com uma senha de login definida via `chpasswd`; VNC reutiliza a mesma conta mas só define uma
senha específica de VNC via `vncpasswd` — não precisa de senha de login Linux própria alguma). A
sequência exata de `vncserver`/`xstartup`/instalação de pacotes só foi exercitada contra o único
template `debian-minimal` (APT) real em uso ativo — vale a pena confirmar novamente após instalar um
`.deb` que inclua isso.

### Podman: pods

Ao lado dos contêineres nspawn, o **New Pod** do menu "+" cria um contêiner real executado com
`podman` (badge `PODMAN` na grade de Máquinas, ao lado de `NSPAWN`/`QEMU`/`HOST`) — mesmas regras de
propriedade/compartilhamento, mesma grade de cartões, mesma relação de página de detalhes que tudo
o mais aqui. Está disponível para qualquer usuário logado, não restrito a administrador; o link só
fica desabilitado enquanto nenhum template com backend podman existir, mesma postura do New Nspawn.

**Criação** (`/containers/new-pod`): Nome, Template (um menu suspenso apenas de templates com
backend podman), Descrição, e um Comando opcional — como uma substituição de `CMD` de Dockerfile;
deixá-lo em branco confia no comando já embutido na imagem. Um shell interativo simples como o
comando sairá em instantes assim que nada mais estiver conectado ao seu stdin, deixando o pod PARADO
em vez de falho — vale saber se um primeiro pod parece desaparecer imediatamente após a criação.
O provisionamento (`ProvisioningService.provisionPod()`) carrega a imagem do template, cria e inicia
o contêiner, concede acesso ao proprietário, resolve e persiste seu endereço interno, e o deixa
direto em **EM EXECUÇÃO** — diferente dos contêineres nspawn, não há fase de INICIANDO/verificação de
prontidão, já que `podman create`+`start` são síncronos e um pod não recebe credencial SSH
autoprovisionada alguma para verificar em primeiro lugar.

**Rede**: pods compartilham a mesma ponte `nspawnbr0` que os contêineres nspawn, mas através de uma
definição de rede podman dedicada (`/etc/containers/networks/nspawnbr0.json`, escrita por
`nspawnmgr-configure-podman-network.sh`) usando o **IPAM host-local** do netavark em vez de DHCP — o
próprio proxy DHCP do netavark transmite a partir do namespace de rede do host, e o kernel nunca faz
esse tráfego voltar à própria fila de recebimento da ponte, um beco sem saída confirmado, não uma
opção não explorada. O pool de endereços é separado da própria faixa DHCP do nspawn para evitar
colisões: pods recebem `10.100.0.192`–`10.100.0.254`, contêineres nspawn mantêm
`10.100.0.2`–`10.100.0.191`. O DNS é definido explicitamente na criação
(`podman create --dns 10.100.0.1 --dns-search internal ...`) em vez de depender de qualquer
configuração entregue via DHCP que um pod nunca recebe — o próprio `aardvark-dns` do podman é
desabilitado nessa rede especificamente para evitar conflito com o próprio dnsmasq do nspawnmgr, já
vinculado a esse mesmo endereço (veja ["Resolvendo contêineres por
nome"](#resolvendo-contêineres-por-nome) acima).

**Ciclo de vida** tem paridade total com os contêineres nspawn — Iniciar/Parar/Reiniciar/Pausar/
Retomar todos despacham para comandos nativos do podman (`start`/`stop`/`kill`/`restart`/
`pause`/`unpause`) em vez de qualquer mecanismo específico do nspawn. Um **`ContainerLivenessPollingService`**
separado reverifica o status real do podman de todo pod EM EXECUÇÃO (e o status real da unidade de
toda VM QEMU EM EXECUÇÃO — veja abaixo) em sua própria programação de ~30s e vira o próprio estado do
nspawnmgr para `PARADO` no momento em que a realidade discorda — necessário porque um pod pode sair
completamente por conta própria (um comando de manutenção ausente ou ruim, veja o campo Comando
acima) sem nada mais no aplicativo jamais percebendo, já que pods pulam inteiramente o caminho de
verificação de prontidão exclusivo do nspawn. Pods `PAUSADOS` não são verificados.

**Acesso**: SSH/RDP/VNC são **apenas com credenciais solicitadas**, o mesmo mecanismo condicionado à
alcançabilidade que Hosts e contêineres descobertos usam ([§ acima](#acesso-remoto-para-contêineres-que-o-nspawnmgr-não-configurou-ele-mesmo))
— habilitado por protocolo a partir da própria página de detalhes do pod assim que o próprio serviço
do convidado estiver de fato escutando. Um pod nunca recebe uma credencial autogerada da forma que o
acesso SSH de um contêiner nspawn recebe.

**Arquivos** funciona via `podman mount`, que expõe o sistema de arquivos overlay mesclado do
contêiner como um caminho de host comum — o mesmo código de navegar/enviar/baixar que os contêineres
nspawn usam então roda diretamente contra esse caminho.

**Scripts** rodam via `podman exec -i <nome> sh -s` (stdin encanado, um código de saída real de
volta ao nspawnmgr). Abortar é uma aproximação mais restrita do que a própria eliminação de unidade
transitória do nspawn: o corpo do script recebe o prefixo `echo $$ > <pidfile>`, e Abortar envia
`kill -9` para esse grupo de processo registrado — uma eliminação de grupo de processo real, mas não
uma verdadeiramente de todo o cgroup da forma que o abortar do nspawn é, documentado no código como
um estreitamento conhecido e deliberado, não um bug.

**Explicitamente não oferecido para um pod** (todos presentes para contêineres nspawn): nenhuma
credencial SSH/RDP/VNC autoprovisionada, nenhuma instalação de gerenciador de desktop, nenhum
mapeamento de porta de entrada personalizado, nenhuma alternância de firewall de saída (um pod já
tem acesso real à rede via netavark — não há nada a controlar), nenhuma montagem de ISO, nenhuma
configuração de início automático/exigências no estilo `machinectl`.

**Templates** vivem sob `TEMPLATES_DIR/podman/<nome>.tar` — um arquivo `podman save`, carregado via
`podman load` no momento da criação, distinto da convenção de tar simples do nspawn. Popule um deles
puxando diretamente de um registro (`nspawnmgr-podman-pull-template.sh`) ou convertendo um template
nspawn já existente (`nspawnmgr-podman-convert-nspawn-to-podman.sh`, e o inverso,
`nspawnmgr-podman-convert-podman-to-nspawn.sh`, para ir na outra direção). Atualmente não há
conveniência de "criar template a partir deste pod" da forma que a própria página de detalhes de uma
máquina nspawn ou QEMU parada oferece — apenas puxadas ou conversões novas.

Não existe suíte de testes automatizada dedicada para o backend podman (nenhuma classe de teste
`*Podman*`) — é coberto pela suíte de testes geral rodando contra simulações, mais dev-stack manual e
clique-a-clique ao vivo na yoga. Tanto a correção de DNS quanto a decisão de rede IPAM host-local do
netavark acima estão confirmadas ao vivo (veja os próprios comentários de cabeçalho de
`nspawnmgr-configure-podman-network.sh` e `nspawnmgr-podman-create-container.sh`) — a aproximação de
abortar via eliminação de grupo de processo é a principal lacuna conhecida e deliberada.

### QEMU: máquinas virtuais

Ao lado dos contêineres nspawn e pods podman, o **New QEMU** do menu "+" cria uma máquina virtual
QEMU/KVM real (badge `QEMU`), na mesma grade de Máquinas com as mesmas regras de
propriedade/compartilhamento. Disponível para qualquer usuário logado; o link fica desabilitado
enquanto o QEMU não estiver instalado no host (veja a página de Diagnóstico).

**Criação** (`/containers/new-qemu`): Nome; origem do disco — **Disco vazio** (um tamanho em GB) ou
**A partir de template** (clonar o próprio disco de um Template já existente com backend QEMU),
mutuamente exclusivos; **Tipo de processador**; **Número de CPUs**; **Memória (MB)**; **Placa de
rede** (modelo de dispositivo NIC — `virtio-net-pci` por padrão, ou `e1000`/`rtl8139`/`pcnet` para
sistemas operacionais convidados que precisam de um específico, ex.: o FreeDOS normalmente precisa
de `pcnet`); **Dispositivo apontador** (`PS/2` por padrão, ou `USB tablet`, que corrige a deriva do
cursor do mouse sob VNC para convidados com GUI — mas convidados da família DOS não têm pilha de
driver USB alguma e precisam de PS/2, motivo pelo qual permanece o padrão em vez de USB tablet); e
um **ISO de inicialização** opcional.

`POST /api/containers/qemu` valida que exatamente um dos campos de tamanho-de-disco/template está
definido, então `ProvisioningService.createPendingQemu()` persiste a linha e `provisionQemu()` faz o
trabalho real: clona o disco do template ou cria um novo vazio, aloca uma porta VNC, escreve a
unidade systemd da VM, a inicia, gera e armazena uma senha VNC, e cria uma conexão VNC do Guacamole
correspondente — pousando em **EM EXECUÇÃO** imediatamente, o mesmo raciocínio de lançamento síncrono
dos pods acima (sem verificação de INICIANDO/prontidão). Um `QemuAddressPollingService` separado e
assíncrono tenta resolver um IP de convidado depois, puramente para fins de SSH — "ainda não pronto,
possivelmente por muito tempo" é o estado esperado e normal para uma VM recém-criada que pode nem
sequer ter um sistema operacional convidado instalado em seu disco ainda.

**Criação de disco** (`nspawnmgr-qemu-create-disk.sh`) é um simples
`qemu-img create -f qcow2 <path> <size>G` sob `/var/lib/nspawnmgr/qemu-disks/`. Mesma camada de
sudo com SENHA que qualquer outro artefato persistente novo ([§3](#3-a-conta-ssh-com-capacidade-de-sudo))
— realmente iniciar a VM depois é uma etapa NOPASSWD separada.

**A unidade systemd da VM** (`nspawnmgr-qemu-write-unit.sh`) é uma unidade real e persistente em
`/etc/systemd/system/nspawnmgr-qemu-<nome>.service` — reescrita, não apenas escrita uma vez, tanto na
criação quanto sempre que o ISO montado muda enquanto a VM está parada (veja abaixo). É persistente
em vez de uma invocação transitória de `systemd-run` porque um simples `systemctl start/stop` contra
ela (que é como o nspawnmgr sempre conduz o ciclo de vida de uma VM QEMU) recebe apenas um nome de
máquina puro, sem nada específico da VM a partir do qual reconstruir uma invocação. Sua linha
`ExecStart` cobre: as flags de memória/modelo-de-CPU/contagem-de-CPU/`-enable-kvm` (KVM
autodetectado via a existência de `/dev/kvm`); o disco qcow2 como uma unidade virtio; a placa de rede
na `nspawnbr0` com um endereço MAC derivado deterministicamente do nome da VM (`52:54:00:` + os
primeiros 3 bytes de um hash md5 do nome — o script de resolução de endereço tem que derivar o
valor idêntico independentemente, já que nenhum dos dois scripts o persiste); as flags de dispositivo
apontador (vazias para PS/2, `-usb -device usb-tablet` para USB tablet); o listener VNC; um monitor
QEMU via socket Unix; e a ordem de boot (`-cdrom ... -boot order=d` quando um ISO está montado,
`-boot order=c` caso contrário). Recorre a `/usr/libexec/qemu-kvm` quando `qemu-system-x86_64` não
está no `PATH` (uma peculiaridade de empacotamento do Fedora/RHEL, o mesmo fallback que
`nspawnmgr-diag-check-qemu.sh` já usa).

**Acesso VNC**: a porta é alocada a partir de uma faixa configurável pelo administrador
([`/admin/settings`](#configurações-editáveis-em-tempo-real-adminsettings), validada para começar em
`5900` ou acima — a própria sintaxe `-vnc host:display` do QEMU endereça um número de display, e
`display = porta - 5900`), escolhendo a porta livre mais baixa ainda não reivindicada por outra VM.
O listener sempre se vincula ao próprio endereço de gateway da `nspawnbr0` (`10.100.0.1`) — diferente
de nspawn/podman, onde o Guacamole disca diretamente para o próprio endereço interno de um contêiner,
o console de hipervisor de toda VM QEMU compartilha um endereço e é diferenciado apenas por porta.
Uma conexão VNC do Guacamole com uma senha gerada é criada automaticamente no momento do
provisionamento — nada para o proprietário habilitar, simplesmente já está lá. O próprio QEMU não
persiste essa senha entre reinícios, então `ContainerLifecycleService` reaplica a credencial
armazenada através do monitor HMP (veja abaixo) a cada início/reinício.

**O monitor HMP** é apenas interno — não há interface para enviar comandos de monitor arbitrários.
`nspawnmgr-qemu-monitor-exec.sh` retransmite uma linha HMP de cada vez via SSH para o socket Unix do
monitor da VM via `socat` (fechando a conexão 2 segundos depois que o QEMU para de responder, já que
o REPL em texto puro do HMP não tem enquadramento limpo por resposta para detectar conclusão — um
ponto de partida, documentado como ainda não verificado contra um monitor real do
`qemu-system-x86_64`). Ele apoia: Parar graciosamente (`system_powerdown`, uma solicitação ACPI —
sem efeito se nenhum sistema operacional convidado estiver instalado ainda, por design, não um bug);
Pausar/Retomar (`stop`/`cont` — o próprio equivalente do QEMU, não o freezer de cgroup que os
contêineres nspawn usam); reaplicar a senha VNC acima; e troca de ISO ao vivo (`change ide1-cd0`/
`eject ide1-cd0`).

**O acesso a Arquivos não está disponível para uma VM QEMU** — diferente do `podman mount` do
podman, não há diretório do lado do host para navegar para uma VM cujo armazenamento é um único
arquivo de disco qcow2, e o acesso real do lado do convidado (SFTP sobre a própria conexão SSH da
VM, uma vez habilitado) ainda não foi construído. O botão FILES fica desabilitado no cartão de uma
VM QEMU por esse motivo; planejado para uma versão futura.

**A montagem de ISO** reutiliza o mesmo cache de pacotes `PackageManager.ISO` que os contêineres
nspawn ([§ acima](#mídia-removível-imagens-iso)). Diferente do bind-mount estático do nspawn (que só
tem efeito na próxima inicialização da VM), o QEMU pode **trocar ao vivo** o disco montado através do
monitor HMP enquanto a VM está atualmente em execução, e separadamente persiste a mesma escolha no
arquivo de unidade (via a mesma reescrita de `nspawnmgr-qemu-write-unit.sh` mencionada acima) para
que também esteja correta na próxima vez que a VM iniciar a frio.

**Templates**: clonar o disco de uma VM a partir de um Template já existente com backend QEMU
(`TEMPLATES_DIR/qemu/<nome>.qcow2`) tem suporte total junto com o caminho de
disco-vazio-mais-ISO descrito acima — escolha **A partir de template** no formulário New QEMU. A
página de detalhes de uma VM parada também tem um campo "Criar template a partir desta máquina", a
mesma convenção que os contêineres nspawn usam, para tirar um snapshot do disco atual de uma VM em um
template novo e independente.

**Ciclo de vida** tem paridade total com nspawn/podman através da unidade systemd persistente acima,
mais o monitor HMP para as operações que o próprio QEMU precisa que sejam solicitadas graciosamente:
Iniciar, Forçar parada, e Reiniciar são simples `systemctl start/stop/restart` contra a própria
unidade da VM; Parar graciosamente e Pausar/Retomar passam pelo HMP como descrito acima, em vez de
`systemctl freeze`/`thaw`.

**Reconciliação de falha**: o mesmo `ContainerLivenessPollingService` descrito acima para podman
também cobre QEMU — a própria unidade de toda VM EM EXECUÇÃO é reverificada (`systemctl is-active`)
na mesma programação de ~30s, e o próprio estado do nspawnmgr vira para `PARADO` no momento em que a
própria unidade parou ou desapareceu de baixo dela. **Ainda um limite real, não totalmente
resolvido**: isso só detecta a própria unidade/processo desaparecendo, não uma falha exclusiva do
sistema operacional convidado onde o processo permanece vivo mas o que está rodando dentro travou ou
morreu — `systemctl is-active` não tem visibilidade alguma sobre isso, e nenhum dos dois backends
oferece uma forma de perguntar. Vale a pena ter em mente se o badge de uma VM alguma vez parecer
discordar da realidade apesar do processo ainda estar tecnicamente rodando.

Não existe suíte de testes automatizada dedicada para o backend QEMU tampouco (nenhuma classe de
teste `*Qemu*`) — coberto pela suíte geral contra simulações, mais dev-stack manual e clique-a-clique
ao vivo; a configuração de dispositivo apontador especificamente foi confirmada ao vivo contra uma VM
real com KolibriOS na yoga. A heurística de enquadramento de resposta do monitor HMP acima, e algumas
das próprias verificações de `nspawnmgr-diag-check-qemu.sh`, estão explicitamente marcadas como não
verificadas contra um monitor real de `qemu-system-x86_64` em seus próprios comentários de cabeçalho.

**Descobrir máquinas** ([§ acima](#descobrindo-máquinas-criadas-fora-do-nspawnmgr)) cobre os três
backends em um clique — ele roda uma passagem separada sobre `machinectl`, `podman`, e as próprias
unidades systemd do QEMU cada, registrando qualquer coisa não rastreada que encontre em qualquer um
deles, pulando um backend completamente se ele não estiver instalado no host de forma alguma.

### Instalação de pacotes: baixados primeiro, não instalados diretamente a partir de uma busca de rede ao vivo

Um gerenciador de pacotes executado *de dentro* de um contêiner em execução foi confirmado como
não confiável ao resolver seus próprios espelhos, mesmo quando a própria rede/DNS do host funciona
bem. SSH, RDP, VNC, e o pacote do gerenciador de desktop recebem todos o mesmo tratamento: o
nspawnmgr os baixa (com seu fechamento completo de dependências, apenas download — nada é instalado
ainda) antes de executar a instalação real *dentro* do contêiner. Aplica-se a templates **APT, DNF, e
PACMAN** usando os comandos de instalação padrão (não substituídos) — uma substituição de comando de
instalação personalizada não pode ser analisada com segurança em busca de nomes de pacotes para
pré-buscar, e recorre à instalação apenas-dentro-do-contêiner de hoje (que precisa que a própria
rede/DNS do contêiner realmente funcione). **APK** é excluído inteiramente: sua própria instalação
local já resolve dependências a partir de repositórios configurados por conta própria, sem
necessidade de pré-busca (moot de qualquer forma — contêineres baseados em Alpine não funcionam
totalmente neste aplicativo hoje, veja abaixo).

**A própria etapa de download do APT roda do lado do host** — um processo apontado diretamente para
o próprio diretório de rootfs do contêiner (`apt-get -o Dir=<rootfs>`), usando a própria rede
funcional do host — já que `apt-get` sempre está no próprio `PATH` deste host (o `.deb` deste
projeto só tem como alvo Debian/Ubuntu). **DNF e PACMAN não podem fazer isso**: nenhum dos dois
jamais está no próprio `PATH` deste host de forma alguma, então a própria etapa de download deles em
vez disso roda *dentro do próprio contêiner*, via `systemd-run --machine=` (a mesma primitiva de
execução não interativa dentro do contêiner que a etapa de instalação real já usa) — apenas download,
igual ao APT, então ainda não toca no estado de pacote instalado do dpkg/rpm/pacman. Uma
consequência: DNF/PACMAN não têm a própria reutilização entre-contêineres "um pacote já em cache e
ainda válido nunca é buscado de novo" que o APT tem (isso depende de um diretório de cache simples
do lado do host que o dnf/pacman rodando *dentro* do próprio namespace de montagem de um contêiner
não consegue ver) — toda pré-busca DNF/PACMAN baixa tudo de novo. Todos os três ainda colocam em
cache o fechamento sob `/var/cache/nspawnmgr/packages/<manager>/auto/` para a visibilidade da página
de administração de Pacotes, independentemente de onde o próprio download rodou.

Uma exceção: GNOME/KDE no DNF instalam via um *grupo* comps (`dnf group install`), não um pacote
simples e nomeado — `dnf --downloadonly` (o que a pré-busca usa) não tem equivalente para
resolver/colocar em cache a filiação de um grupo inteiro com antecedência, apenas pacotes
individuais, então essas duas combinações deliberadamente pulam a pré-busca e caem direto na
instalação de grupo dentro do contêiner (precisando da própria rede/DNS do contêiner, o mesmo que um
comando substituído precisaria). O Xfce não tem esse problema — confirmado ao vivo, o Fedora o
disponibiliza como um pacote simples e nomeado (`xfce4`), não um grupo comps de forma alguma.

Essa própria etapa de instalação real dentro do contêiner nunca reexecuta a própria atualização de
metadados de `apt-get update`/`dnf`: é redundante, já que a etapa de pré-download já atualizou o
índice (do lado do host para APT, dentro do contêiner para DNF/PACMAN) momentos antes, então o que a
etapa de instalação lê já está atualizado, e todo pacote de que precisa já está sentado no próprio
cache local do contêiner — cada script de pré-busca deixa uma cópia ali exatamente por esse motivo.

O pacote de nível superior em si (não suas dependências transitivas, que permanecem um detalhe de
implementação do diretório de cache) também é registrado no cache de administração de **Pacotes**
descrito logo abaixo, então o que o nspawnmgr buscou para seu próprio provisionamento fica visível e
reutilizável ali também, não apenas um efeito colateral oculto da criação de um contêiner.

### Fazendo upload e instalando pacotes arbitrários

Administradores também podem fazer upload de qualquer arquivo de pacote diretamente: **Pacotes** (a
partir da lista de contêineres, apenas administrador) aceita um arquivo `.deb`/`.rpm`/o que quer que
seu gerenciador de pacotes use, mais uma descrição opcional. Todo proprietário de contêiner então vê
uma seção correspondente **Instalar pacote** na própria página de detalhes de seu contêiner (apenas
pacotes para o próprio gerenciador de pacotes desse contêiner são oferecidos) — escolher um e clicar
em Instalar copia-o para o contêiner, então, para pacotes **APT, DNF, e PACMAN**, primeiro *simula*
a instalação (`apt-get install -s` / `dnf install --assumeno` / `pacman -U --print`, nenhuma mudança
feita) contra o próprio estado do contêiner para encontrar qualquer dependência que ele ainda não
tenha. Qualquer coisa faltando é buscada da mesma forma que o provisionamento de
SSH/RDP/VNC/gerenciador-de-desktop já faz (veja acima — do lado do host para APT, dentro do próprio
contêiner via `systemd-run --machine=` para DNF/PACMAN, já que nenhum dos dois jamais está no
próprio `PATH` deste host) e registrada aqui no cache de pacotes também, então a instalação real roda
via o próprio comando de instalação de arquivo local do gerenciador de pacotes
(`apt-get install <path>` / `dnf install <path>` / `pacman -U --noconfirm <path>`) — sua própria
resolução de dependências pega tanto o arquivo enviado quanto o que acabou de ser pré-buscado em uma
única passagem coerente. A própria instalação local do DNF/PACMAN normalmente resolveria dependências
diretamente do próprio acesso à rede do contêiner, o mesmo que cada um faz para qualquer pacote
nomeado — a etapa de pré-busca roda de qualquer forma, deliberadamente, por consistência com a
própria postura do APT de "nunca deixar um contêiner alcançar a rede diretamente para uma busca de
espelho de gerenciador de pacotes ao vivo" (a própria pré-busca do DNF/PACMAN ainda precisa da rede
do contêiner para o próprio download dentro do contêiner — só mantém essa necessidade contida a uma
única etapa não interativa, apenas de download, em vez do comando de instalação real). Esta subetapa
precisa da mesma camada de senha de sudo que a criação de contêiner, então falha completamente (sem
instalação parcial silenciosa) se nenhum segredo de sudo armazenado estiver configurado e a
solicitação não tiver fornecido um. **O suporte de DNF e PACMAN para instalar um pacote enviado
*dentro de um contêiner Fedora/Arch* não está verificado** — distinto de instalar o *próprio
nspawnmgr* em um host RPM/Arch real, o que está verificado (veja as seções de instalação de pacote
RPM e Arch acima); este fluxo específico de upload de pacote dentro do contêiner nunca foi
exercitado contra um contêiner Fedora/Arch real, apenas construído conforme o contrato de CLI
documentado de cada ferramenta com o máximo cuidado possível — relate qualquer discrepância ao vivo
encontrada. **PACMAN é o mais especulativo dos dois**: diferente de `apt-get install -s`/
`dnf install --assumeno`, que são os próprios modos de simulação bem documentados do apt/dnf, o
comportamento de `pacman -U --print` para uma simulação completa de fechamento de dependências de
arquivo local nunca foi exercitado em lugar algum deste projeto, nem mesmo manualmente. Pacotes
**APK** pulam tudo isso e apenas executam uma única instalação local (`apk add <path>`) sem
resolução de dependências — uma dependência faltando ali ainda é um erro visível na saída, não
corrigido automaticamente (a própria instalação local do APK de fato resolveria dependências a
partir de repositórios configurados, mas contêineres baseados em Alpine não funcionam totalmente
neste aplicativo hoje de qualquer forma — veja abaixo). Pacotes que o nspawnmgr baixou
automaticamente (seja para seu próprio provisionamento de SSH/RDP/VNC/gerenciador-de-desktop, ou como
uma dependência buscada por este fluxo) aparecem aqui também, atribuídos à criação ou instalação de
qualquer contêiner que os tenha buscado primeiro, ao lado de qualquer coisa que um administrador
tenha enviado manualmente.

O botão **"Mostrar dependências transitivas"** da página de Pacotes preenche a lacuna que isso
deliberadamente deixa: escolha um gerenciador de pacotes (APT/DNF/PACMAN, os mesmos três com um
diretório de cache de pré-busca) e ele lista todo arquivo de fato sentado no próprio diretório
compartilhado `/var/cache/nspawnmgr/packages/<manager>/auto` desse gerenciador, com o tamanho em
bytes. Isso é gerado na hora, executando um comando e lendo o diretório real toda vez que o botão é
clicado (`nspawnmgr-list-auto-cache.sh`, um script wrapper NOPASSWD e somente leitura) — nada disso
é armazenado no banco de dados, diferente dos pacotes de nível superior na tabela acima. Útil para
confirmar que uma dependência de fato chegou ao disco, ou para estimar o quanto esse diretório de
cache compartilhado um dado gerenciador de pacotes acumulou ao longo do tempo.

### Mídia removível (imagens ISO)

**ISO** é um valor real de `PackageManager`, não um cache/entidade/página de administração
separado — faça upload de um a partir da mesma página de administração de **Pacotes**, exatamente
como um `.deb`/`.rpm`, escolhendo `ISO` em vez de `APT`/`DNF`/`APK`/`PACMAN`. A maquinaria de
instalação no estilo `.deb`/`.rpm` não se aplica a ele (não há comando de instalação para `ISO`, e
`Template.packageManager` nunca pode ser `ISO` — o próprio menu suspenso do formulário de
administração de Templates o exclui), mas o caminho de upload/cache/publicação-via-CI é idêntico de
qualquer forma, por escolha deliberada em vez de construir um segundo caminho paralelo. Qualquer
proprietário de contêiner pode então configurar um ISO enviado a partir da própria página de
detalhes de seu contêiner, na seção "Mídia removível" — no máximo um por contêiner por vez, como uma
unidade de CD real, sempre montado somente leitura em `/mnt/cdrom` fixo. Montar um ISO diferente
enquanto um já está configurado ejeta automaticamente o antigo primeiro; não há uma etapa separada
de ejetar-depois-montar.

**Uma configuração persistente e declarativa — exatamente como os [mapeamentos de porta
personalizados](#mapeamentos-de-porta-personalizados-e-acesso-de-saída), não uma operação ao vivo.**
Montar/ejetar reescreve o arquivo `.nspawn` do contêiner imediatamente (uma linha `[Files]`
`BindReadOnly=` estática), mas só tem efeito na próxima vez que o contêiner é (re)iniciado, e
permanece configurado entre reinícios até ser explicitamente mudado ou ejetado — isso *não* exige
que o contêiner esteja em execução para configurar, e uma parada/reinício *não* o limpa. A metade do
lado do host (um arquivo ISO montado via loop em um caminho fixo por contêiner,
`nspawnmgr-mount-iso.sh`/`nspawnmgr-unmount-iso.sh`) é configurada/desmontada assim que você
monta/ejeta, independentemente de o contêiner estar em execução naquele momento; uma reinicialização
do host, porém, não restabelece essa montagem via loop por conta própria atualmente, então um
contêiner iniciado após uma reinicialização do host com um ISO ainda configurado falhará ao iniciar
até que isso seja resolvido manualmente (`mount -o loop,ro <iso> /var/lib/nspawnmgr/iso-mounts/<name>`)
— uma limitação conhecida, não reconciliada automaticamente hoje.

**Isso torna o `systemd-networkd` um pré-requisito rígido, não apenas uma comodidade para acesso de
saída** — o próprio postinst do nspawnmgr o usa para criar e configurar a própria `nspawnbr0` (veja
acima), e tanto a verificação de prontidão do nspawnmgr quanto o `guacd` discam diretamente para o
endereço `host0` de um contêiner assim que ele tiver um, então um contêiner que nunca obtém um
(`host0` nunca habilitado dentro do template — veja etapa 2 abaixo) nunca sai de INICIANDO, ponto
final, não apenas lentamente. Audite qualquer um dos seus próprios templates em busca de
`systemctl enable systemd-networkd` se contêineres pararem de alcançar EM EXECUÇÃO.

O único encaminhamento de entrada restante em nível de host são os [mapeamentos de porta
personalizados](#mapeamentos-de-porta-personalizados-e-acesso-de-saída) — inteiramente opcional,
gerenciado pelo proprietário, e usando o mesmo mecanismo `.nspawn` de
`Port=tcp:<host-port>:<container-port>` (que o próprio `systemd-nspawn` ainda configura como regras
DNAT automaticamente ao iniciar).

Concretamente, para terminar de configurar isso:

1. `sudo systemctl enable --now systemd-networkd` (**Diagnóstico de rede** tem uma verificação +
   correção em um clique para isso), e `sudo sysctl -w net.ipv4.ip_forward=1` (persista sob
   `/etc/sysctl.d/`) — `IPMasquerade=yes` no próprio arquivo `.network` da `nspawnbr0` (veja acima)
   adiciona a regra NAT, mas o encaminhamento real de pacotes entre interfaces é uma configuração
   separada, em nível de kernel, que este pacote não ativa para você. Se o NetworkManager/ifupdown
   já gerencia sua NIC principal, diga a ele para deixar a `nspawnbr0` em paz (ex.: o
   `unmanaged-devices=interface-name:nspawnbr0` do NetworkManager.conf) para que o networkd
   permaneça livre para geri-la.
2. Dentro do **template** do contêiner, antes de preparar (a mesma etapa da preparação do
   `openssh-server` no [§2](#templates-de-contêiner-sistemas-de-arquivos-raiz-base)):
   `systemctl enable systemd-networkd` para que a `host0` de fato obtenha sua configuração DHCP da
   ponte — a saída do `debootstrap` não a habilita por padrão. **Obrigatório**, não opcional: pule
   isso e contêineres desse template nunca saem de INICIANDO.
3. Inicie (ou reinicie) um contêiner — `machinectl start` escraviza seu veth na `nspawnbr0`, ele
   recebe um endereço e uma rota via DHCP da ponte, e o nspawnmgr/`guacd` agora podem alcançá-lo
   diretamente.

### Resolvendo contêineres por nome

Contêineres gerenciados já conseguem se alcançar via IP (nada na própria configuração de firewall do
nspawnmgr bloqueia tráfego `FORWARD` de contêiner-para-contêiner — a regra DROP da cadeia
`NSPAWNMGR-OUTBOUND` só corresponde aos pacotes de saída *próprios* de um contêiner,
independentemente do destino). O que falta sem esta seção é uma forma de procurar um par pelo nome
em vez de seu endereço interno, que é atribuído via DHCP por contêiner e pode mudar entre reinícios.

`dnsmasq` é uma dependência real do `apt` deste pacote (diferente de guacd/Tomcat, que são
empacotados junto — veja [§2](#2-pré-requisitos-do-host); o comportamento de servir arquivo de hosts
do `dnsmasq` é simples e estável o suficiente entre versões que não há necessidade de fixar uma).
Instalado e configurado automaticamente: vinculado apenas à `nspawnbr0` (nunca alcançável a partir da
própria LAN/interface de uplink do host — não é, e nunca deve se tornar, um resolvedor aberto),
servindo o que quer que esteja em `/etc/nspawnmgr/dns-hosts`. Todo contêiner também recebe o próprio
endereço da `nspawnbr0` (`10.100.0.1`) como seu servidor DNS automaticamente, diretamente do próprio
arquivo `.network` da `nspawnbr0` — nenhuma etapa extra de administrador necessária. O nspawnmgr
regenera `/etc/nspawnmgr/dns-hosts` (`ContainerDnsSyncService`, a cada ~15s) a partir do próprio nome
e endereço interno de todo contêiner GERENCIADO atualmente EM EXECUÇÃO — o mesmo endereço que
guacd/prontidão já resolvem (veja acima), então nada novo precisa ser descoberto. O dnsmasq não
percebe um arquivo `addn-hosts` mudado por conta própria (sem recarga automática/baseada em inotify
para ele, apenas SIGHUP ou um reinício), então toda escrita é seguida por uma recarga
(`nspawnmgr-reload-dnsmasq.sh`/`DnsReloader`) — sem ela, contêineres continuariam falhando ao
resolver uns aos outros, não importa quão atual o arquivo no disco realmente esteja.

Como essa instância do `dnsmasq` roda diretamente no host, ela também lê e serve o próprio
`/etc/hosts` do host para contêineres por padrão (confirmado como o comportamento desejado ao vivo)
— as próprias entradas estáticas de LAN de um administrador ali (ex.: `192.168.1.15 acer`) se tornam
resolvíveis de dentro de todo contêiner também, não apenas do próprio host. A única ressalva: se
`/etc/hosts` também mapear o próprio nome de host puro do host para um endereço loopback (a
convenção própria do Debian, `127.0.1.1 <hostname>`) *e* esse mesmo nome puro estiver definido como
a configuração de nome de host externo abaixo, as duas fontes colidem e o dnsmasq pode responder com
qualquer um dos dois endereços — evite escolher um nome curto já mapeado em `/etc/hosts` para essa
configuração.

`/etc/nspawnmgr/dns-hosts` também carrega mais uma entrada fixa: o próprio nome de host externo do
host (`nspawnmgr.host.external-hostname`/`HOST_EXTERNAL_HOSTNAME` — detectado automaticamente no
momento da instalação por `setup-sudo-account.sh`, editável ao vivo depois em
[`/admin/settings`](#configurações-editáveis-em-tempo-real-adminsettings)), apontando para o próprio
endereço fixo da `nspawnbr0` (`10.100.0.1`). Um contêiner não tem outra rota de volta ao host de
forma alguma — isso é o que permite resolver o próprio nome do host para alcançar qualquer coisa que
o host encaminhe de volta (ex.: um [mapeamento de porta
personalizado](#mapeamentos-de-porta-personalizados-e-acesso-de-saída)). Mantido sincronizado da
mesma forma e na mesma programação que as entradas de contêiner acima; omitido inteiramente enquanto
ainda estiver em seu padrão não configurado `localhost` (mapear "localhost" em si para `10.100.0.1`
seria ativamente errado, não apenas inútil).

Essa mesma instância do `dnsmasq` também é o *único* servidor DNS de todo contêiner — não apenas
para nomes `.internal` — então ela também encaminha qualquer coisa fora de `.internal` para os
resolvedores upstream configurados, `nspawnmgr.dns.upstream-servers` (padrão `1.1.1.1,9.9.9.9`),
editável ao vivo em [`/admin/settings`](#configurações-editáveis-em-tempo-real-adminsettings) — ex.:
para apontar contêineres para um servidor DNS corporativo em vez disso. Sem algum upstream
configurado, o próprio `dnf`/`pacman`/`apt` de um contêiner (buscando dos próprios espelhos de
pacote reais) ou qualquer outra coisa que precise de um nome de host real de internet falha
completamente com "Could not resolve host" — confirmado ao vivo. Ainda não é um resolvedor aberto no
sentido acima: o encaminhamento acontece através da própria rota normal de internet do host, e o
próprio `dnsmasq` ainda está vinculado apenas à `nspawnbr0`, inalcançável de fora da ponte de
contêiner.

Os servidores upstream vivem em seu próprio arquivo, `/etc/dnsmasq.d/nspawnmgr-upstream.conf` —
separado do `nspawnmgr.conf` principal acima — incluído automaticamente junto com ele pelo próprio
`conf-dir=/etc/dnsmasq.d/` do dnsmasq (padrão do Debian em `/etc/dnsmasq.conf`), nenhuma diretiva
extra necessária. `ContainerDnsSyncService` o mantém sincronizado com a configuração atual da mesma
forma que mantém `dns-hosts` sincronizado com contêineres em execução (verificado a cada ~15s, só
reescrito quando o valor efetivo realmente muda). O `postinst` o semeia com o mesmo padrão
`1.1.1.1`/`9.9.9.9` na primeira instalação (apenas se o arquivo ainda não existir), então a resolução
upstream funciona desde a primeira inicialização, antes mesmo do próprio nspawnmgr estar de pé para
assumir a sincronização.

Contêineres se resolvem uns aos outros pelo próprio nome simples do nspawnmgr (`b1`) ou por um FQDN
sob o sufixo fixo `.internal` (`b1.internal`) — as opções `domain=`/`expand-hosts` do dnsmasq servem
ambas as formas a partir das mesmas entradas de `dns-hosts` automaticamente, sem configuração
separada. `internal` é o TLD de uso especial reservado da IANA exatamente para isso (RFC 8375, a
mesma categoria de `home.arpa`), não um domínio inventado, então é garantido que nunca colidirá com
um público real. O escopo é apenas contêineres GERENCIADOS (hosts EXTERNOS, configurados pelo
administrador, já têm seu próprio `hostname` e não são adicionados aqui), e o namespace é plano em
todos eles — isso é puramente alcançabilidade em nível de rede, independente de quais contêineres um
dado usuário pode ver ou conectar na interface web (a grade de Máquinas só mostra máquinas que um
usuário possui ou com as quais foi compartilhado, exceto para um administrador, que vê tudo
independentemente da propriedade).

Duas peças mais são necessárias para que isso funcione de ponta a ponta:

- **O lado do contêiner**: o `systemd-resolved` se recusa a enviar um nome não qualificado (sem
  ponto) como `b2` para um servidor DNS real de forma alguma — apenas para LLMNR/mDNS — a menos que
  o link tenha um domínio de roteamento/pesquisa configurado para qualificá-lo. O DHCP poderia
  fornecer isso, mas isso exige que o próprio `80-container-host0.network` do contêiner (gerado pelo
  próprio `systemd-nspawn`, não algo que este template controla) opte por `UseDomains=yes`, o que
  não faz por padrão. O template em vez disso envia um drop-in estático em
  `/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf`
  (`[Network]\nDomains=internal`), mesclado pelo nome de arquivo da mesma forma que um drop-in de
  unidade systemd é — contorna o DHCP inteiramente e não depende de nenhuma opção realmente sendo
  enviada.
- **O lado do dnsmasq**: `domain=`/`expand-hosts` sozinhos só controlam o sufixo com que o dnsmasq
  *decora suas próprias respostas* — eles não o tornam autoritativo para uma consulta que já
  *chega* pré-qualificada (exatamente o que um contêiner com o domínio de roteamento acima agora
  envia). Sem também definir `local=/internal/`, uma consulta `b2.internal` recebida cai
  completamente através da correspondência de hosts/`addn-hosts` e é encaminhada upstream como
  qualquer outro nome — `.internal` não existe publicamente, então isso simplesmente falha (e de
  outra forma vazaria nomes de contêiner para o resolvedor público configurado). `local=/internal/`
  marca `.internal` como a própria zona autoritativa do dnsmasq: responder apenas a partir dos
  próprios dados de hosts, `NXDOMAIN` para qualquer coisa genuinamente desconhecida ali, nunca
  encaminhar.

Se você algum dia editar manualmente qualquer um dos arquivos do dnsmasq diretamente em um host em
execução: `domain=`, `expand-hosts`, `local=` (em `nspawnmgr.conf`), e `server=` (em
`nspawnmgr-upstream.conf`) são todos estruturais — o dnsmasq só os analisa na inicialização do
processo, confirmado ao vivo — diferente de `addn-hosts`, que
`DnsReloader.reload()`/`nspawnmgr-reload-dnsmasq.sh` recarrega corretamente a quente via `SIGHUP`.
Um simples `systemctl reload dnsmasq` depois de editar manualmente qualquer um dos estruturais não
tem efeito algum; use `systemctl restart dnsmasq`. `ContainerDnsSyncService` já conhece essa
distinção: uma mudança em `addn-hosts` passa por `DnsReloader.reload()` (SIGHUP) como acima, mas uma
mudança nos servidores upstream passa pelo separado
`DnsReloader.restart()`/`nspawnmgr-restart-dnsmasq.sh` (um `systemctl restart` completo) em vez
disso — usar `reload()` para essa deixaria o arquivo no disco correto enquanto o dnsmasq continuaria
respondendo silenciosamente com o que quer que tenha realmente iniciado por último. Uma instalação/
atualização normal de pacote não precisa de nenhum dos dois: o `postinst` do `.deb` sempre emite seu
próprio `restart` completo quando (re)instala `nspawnmgr.conf`.

### Descobrindo máquinas criadas fora do nspawnmgr

Se uma máquina foi criada manualmente diretamente no host — `machinectl clone`/`debootstrap`/
`import-tar` executados por você mesmo, ou uma imagem restaurada de backup — o nspawnmgr não tem
ideia de que ela existe até que um administrador clique em **Descobrir máquinas** na lista de
contêineres. Isso compara todo nome de imagem que o `machinectl` atualmente conhece contra o próprio
banco de dados do nspawnmgr e registra o que quer que ainda não esteja rastreado como um contêiner
GERENCIADO comum, **pertencente ao administrador que executou a descoberta**. Executá-lo novamente é
seguro — qualquer coisa já rastreada (pelo nome) é pulada.

A descoberta registra a existência da máquina e permite que você a inicie/pare/exclua e a veja
resolvida pelo nome (veja acima). Deliberadamente, ela nunca instala uma conta de administrador
SSH/RDP/VNC da forma que criar um contêiner através do nspawnmgr faz — diferente de um contêiner que
o próprio nspawnmgr provisionou, não há como saber o que já existe dentro de uma imagem construída à
mão, então ela nunca assume um nome de conta ou executa `useradd`/instala um servidor para qualquer
um dos três. O que ela *faz*: logo depois de registrar cada máquina, verifica se SSH (porta 22), RDP
(porta 3389), ou VNC (porta 5900) já está escutando, e se sim, conecta uma conexão Guacamole para ela
automaticamente — no modo de **credenciais solicitadas**, o mesmo mecanismo que a página de Hosts
abaixo usa, então você é solicitado por um nome de usuário/senha a cada vez que se conecta, em vez de
o nspawnmgr gerar e armazenar um. Se nenhuma dessas portas estava aberta no momento da descoberta
(ou você habilitar uma na máquina depois), faça isso manualmente a partir da própria página de
detalhes do contêiner — veja "Acesso remoto" abaixo.

### Acesso remoto para contêineres que o nspawnmgr não configurou ele mesmo

A página de detalhes de um contêiner tem uma seção **Acesso remoto** para cada um dos SSH, RDP, e
VNC sempre que o nspawnmgr não tem credencial gerada alguma para esse protocolo nele — sempre
verdadeiro para um contêiner descoberto, e também verdadeiro para um contêiner comum criado pelo
nspawnmgr se RDP/VNC foi recusado quando ele foi criado. Clicar em **Habilitar acesso SSH/RDP/VNC**
verifica se a porta está de fato escutando agora e, apenas se sim, conecta uma conexão Guacamole de
credenciais solicitadas exatamente como a própria etapa de conexão automática da descoberta acima;
**Desabilitar** a remove novamente. Essa verificação acontece uma vez, no momento em que você clica
em Habilitar — se o serviço dentro do contêiner parar de novo depois, o botão Conectar permanece
habilitado até a próxima tentativa de conexão falhada, em vez do nspawnmgr reexaminar continuamente
todo contêiner em segundo plano.

Esta seção deliberadamente nunca é oferecida para um protocolo que o nspawnmgr já gerencia com uma
credencial gerada real (o SSH de todo contêiner, e o RDP quando solicitado na criação) — essa conexão
é deixada completamente em paz, então esse recurso nunca pode substituir silenciosamente credenciais
geradas que funcionam por uma conexão de credenciais solicitadas.

### Hosts: máquinas externas gerenciadas por administrador

Um **Host** é uma entrada para uma máquina arbitrária na rede que não é um contêiner gerenciado pelo
nspawnmgr de forma alguma — uma máquina Windows já existente, um NAS, o servidor de outra equipe,
qualquer coisa alcançável via SSH/RDP/VNC que seja conveniente acessar através do mesmo fluxo de SSO
do Guacamole que tudo o mais aqui. Não há uma página separada de Hosts: um Host é uma linha
`Container` por baixo (tipo `EXTERNAL`), então ele aparece como um cartão comum — um badge fixo
`HOST` em vez de um badge de backend — bem ao lado das máquinas nspawn/podman/QEMU na grade principal
de **Máquinas**, e sua página de detalhes é a mesma rota `/containers/{id}` que toda outra máquina
usa. Administradores adicionam um a partir do item **New Host** do menu "+" (`/admin/hosts/new`,
apenas administrador): um nome, um nome de host/IP, um nome de usuário proprietário (deve pertencer
a um usuário que já tenha feito login pelo menos uma vez), e qual de SSH/RDP/VNC oferecer mais a
porta para cada um. Um administrador vendo a própria página de detalhes desse host recebe botões
**Editar host** (de volta ao mesmo formulário, em `/admin/hosts/{id}/edit`) e **Excluir host** em seu
painel Gerenciar — não há uma página separada de lista de hosts; o banco de dados é a única fonte da
verdade.

**A visibilidade segue a mesma regra de proprietário/administrador/compartilhado que toda outra
máquina** — um Host não é público apenas porque foi criado por administrador; apenas um
administrador, seu proprietário, ou alguém com quem foi explicitamente compartilhado o vê na própria
grade de Máquinas (`ContainerRepository.findVisibleToUserOrderByName` aplica isso uniformemente
entre linhas de nspawn, podman, QEMU, e Host).

**EM EXECUÇÃO/PARADO é resolvido ao vivo, não armazenado.** Como o nspawnmgr não controla o ciclo de
vida de um Host de forma alguma, o badge de estado dele vem de uma única verificação de
alcançabilidade TCP (`HostLivenessService`) contra qualquer uma das portas SSH/RDP/VNC configuradas
que esteja habilitada — SSH primeiro se presente, depois RDP, depois VNC — em cache por um minuto por
host para que a grade de Máquinas e a própria página de detalhes do host não disparem, cada uma, uma
nova verificação a cada solicitação. Um Host sem nenhum dos três habilitado não tem nada para
verificar e sempre mostra EM EXECUÇÃO.

Conexões sempre solicitam credenciais ao vivo — o nspawnmgr nunca armazena uma senha para um host, o
mesmo mecanismo de credenciais solicitadas que a própria conexão automática da descoberta e a seção
de Acesso remoto por contêiner acima usam ambas.

O campo de nome de host/IP pode ser um nome de host real, não apenas um endereço — em uma instalação
autoalojada, o próprio cliente SSH/RDP/VNC do Guacamole roda dentro do contêiner autoalojado
`nspawnmgr`, cujo único caminho de DNS é o próprio dnsmasq do nspawnmgr (nomes de contêiner mais
resolvedores upstream públicos), sem visibilidade alguma na própria resolução de nomes de uma LAN
privada. Para contornar isso, o nspawnmgr reresolve o próprio nome de host no host subjacente (via a
mesma conta SSH com capacidade de sudo usada para toda outra operação privilegiada) toda vez que
alguém se conecta, e entrega ao Guacamole o endereço resolvido diretamente em vez do nome de host —
então um nome apenas-de-LAN que só o próprio DNS/NetBIOS/mDNS da sua rede conhece ainda funciona, e
um endereço reatribuído por DHCP é captado automaticamente na próxima conexão sem precisar que um
administrador perceba e salve novamente a entrada. Se o nome de host não resolver no host no momento
da conexão, a tentativa de conexão falha com um erro claro em vez de prosseguir com um endereço
obsoleto.

O compartilhamento funciona da mesma forma que para contêineres: o proprietário gerencia quem mais
pode se conectar a partir da própria página de detalhes da entrada. Um administrador que não é o
proprietário vê um botão **Tomar posse** sob Gerenciar ali em vez disso — útil para assumir um Host
(ou qualquer máquina) cujo proprietário já saiu, sem precisar de acesso ao banco de dados.

Os botões SSH/RDP/VNC tanto na grade de Máquinas quanto na própria página de detalhes de um Host
abrem a sessão Guacamole em uma nova aba do navegador, em vez de navegar para longe — útil ao
conectar a várias máquinas a partir da mesma página. Abrir uma a partir do cartão de um Host usa
`/hosts/{name}/session/{protocol}`, seu próprio namespace de URL distinto do
`/containers/{name}/session/{protocol}` de uma máquina comum — um Host é uma linha Container por
baixo como observado acima, mas a URL da própria *sessão* que um usuário de fato vê em seu
navegador deliberadamente não diz "containers" para algo que não é um, do ponto de vista de um
administrador. Ambas as rotas renderizam o template/JS idêntico por baixo (um iframe mais uma
chamada fetch para o mesmo endpoint de API `/api/containers/{id}/session/{protocol}`); só a URL da
página difere. Ambas usam o **nome** da máquina como chave, não seu id numérico — uma escolha
deliberada para que a URL em um link compartilhado ou no histórico de um navegador permaneça
significativa.

### Mapeamentos de porta personalizados e acesso de saída

Além de SSH/RDP acima, o **proprietário** de um contêiner pode se autoatender com mais duas coisas a
partir de sua página de detalhes — nenhuma ação de administrador necessária para nenhuma delas:

- **Mapeamentos de porta de entrada personalizados**: qualquer encaminhamento adicional de
  porta-do-host → porta-do-contêiner TCP ou UDP, com o proprietário escolhendo ambos os números de
  porta exatamente. O nspawnmgr verifica se a porta do host solicitada já não está vinculada por
  outro mapeamento personalizado antes de aceitá-la. Um mapeamento é escrito no arquivo `.nspawn`
  imediatamente, mas só tem efeito na próxima vez que o contêiner é (re)iniciado — adicionar um a um
  contêiner em execução mostra um aviso de "reinício necessário" em vez de reiniciá-lo
  automaticamente.
- **Alternância de acesso à internet de saída**: diferente da configuração de mascaramento em nível
  de host, tudo-ou-nada, acima, cada contêiner pode individualmente ter seu acesso de saída
  bloqueado. O nspawnmgr gerencia isso ele mesmo com uma cadeia iptables dedicada
  `NSPAWNMGR-OUTBOUND` (criada automaticamente na primeira vez que é necessária, chamada a partir do
  topo de `FORWARD`) mantendo uma regra `DROP` por contêiner com saída desabilitada, chaveada na
  própria interface veth do lado do host real desse contêiner — que o nspawnmgr procura
  dinamicamente cada vez (via o ifindex do par do veth), já que, como acima, o nome do veth não é
  uma string previsível derivada do nome do contêiner. Alternar isso tem efeito imediato, sem
  reinício necessário, para um contêiner em execução.
- **Lista de permissões de saída**: enquanto o acesso de saída está desabilitado, o proprietário
  ainda pode abrir uma exceção para destinos específicos — um endereço IPv4 literal, porta, e
  protocolo (TCP/UDP) — ex.: `127.0.0.1` para que o contêiner possa alcançar outro
  contêiner/serviço colocalizado sem conceder acesso geral à internet. Implementado como regras
  ACCEPT antes da regra DROP do contêiner na mesma cadeia `NSPAWNMGR-OUTBOUND`; toda mudança limpa e
  reconstrói as regras desse contêiner do zero em vez de aplicá-las no lugar. Não tem efeito
  enquanto o acesso de saída está habilitado — tudo já é alcançável nesse caso. Também tem efeito
  imediato, sem reinício necessário.

Ambos exigem que o comando `iptables` esteja disponível e utilizável sem senha via a conta com
capacidade de sudo do [§3](#3-a-conta-ssh-com-capacidade-de-sudo) — a mesma conta e mecanismo que o
nspawnmgr já usa para escrever arquivos `.nspawn` e iniciar/parar contêineres.

## 3. A conta SSH com capacidade de sudo

Crie uma conta local dedicada no mesmo host, com acesso sudo restrito, na qual o nspawnmgr se
conectará via SSH (sempre por loopback, `127.0.0.1`) para de fato executar `machinectl`/`systemd-run`
e tocar caminhos pertencentes ao root. **Recomendado:** deixe
`packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh` fazer isso por você — é o mesmo script que o
`postinst` do `.deb` executa, mas é totalmente executável de forma independente, sem construir ou
instalar o pacote de forma alguma:

```bash
sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
```

Executado a partir de um checkout deste repositório (nenhuma flag necessária — ele autodetecta os
irmãos `privileged-scripts/` e `debian/nspawnmgr.sudoers` ao seu lado), ele cria a conta de sistema
`nspawnmgr_exec`, gera e armazena uma senha aleatória para ela, gera um par de chaves SSH, instala os
scripts wrapper referenciados abaixo em `/usr/lib/nspawnmgr/privileged/`, instala e valida a
concessão de sudoers, e adiciona uma exceção `PasswordAuthentication` no sshd para a conta se seu
host a desabilitar globalmente. É idempotente — seguro para executar novamente após uma atualização
ou para captar scripts wrapper atualizados. Veja o próprio comentário de cabeçalho do script para o
detalhe completo.

Se preferir configurar isso inteiramente manualmente (ex.: para usar um nome de conta diferente),
veja o que o próprio script faz como referência — mas observe as duas camadas de privilégio abaixo,
já que um `usermod -aG sudo` genérico (qualquer comando, sempre via senha) não corresponde mais à
forma como o nspawnmgr de fato chama essa conta.

### Duas camadas de privilégio

O acesso sudoers para esta conta é dividido em duas camadas, não uma:

- **NOPASSWD** — os comandos de forma fixa e sempre seguros: `machinectl start/poweroff/terminate/
  reboot/remove/show`, `systemd-run --machine=... --pipe --quiet --wait /bin/sh -s` (executando um
  script de contêiner armazenado — veja "Fronteira de confiança: scripts de contêiner" abaixo para
  saber por que esta forma específica de `systemd-run` é NOPASSWD enquanto a genérica abaixo não é),
  e os scripts wrapper sob `/usr/lib/nspawnmgr/privileged/` que lidam com escrever configurações
  `.nspawn`, excluir os arquivos de um contêiner, e sincronizar o firewall de saída. Essas são ações
  rotineiras, acionadas pelo proprietário (iniciar um contêiner, editar seus mapeamentos de porta,
  excluí-lo, executar um script que escreveu) que nunca devem bloquear esperando por um administrador,
  independentemente de qual modo de criação de contêiner abaixo esteja ativo.
- **Requer senha** (sem a tag `NOPASSWD`) — `systemd-run --machine=... --pipe --quiet --wait`
  (executa conteúdo arbitrário escrito em template como root dentro de um contêiner novo — veja
  "Fronteira de confiança" abaixo), o wrapper `nspawnmgr-clone-template.sh`, e o wrapper
  `nspawnmgr-create-debian-template.sh` (baixa/extrai um rootfs Debian real — veja "Templates de
  contêiner" do §2, o botão "Configurar debian-minimal" da página de administração de Templates). Os
  três são apenas-no-momento-da-criação — os dois primeiros chamados exatamente uma vez por
  contêiner a partir de `ProvisioningService`, o terceiro apenas sob demanda de um administrador
  quando nenhum template existir ainda. Qual senha é usada — e se alguma está sequer disponível sem o
  envolvimento de um administrador — depende do modo abaixo.

Todo comando privilegiado passa por uma dessas duas invocações fixas de script wrapper ou
`machinectl`/`systemd-run` — o nspawnmgr nunca pede ao sudo para executar um script inline
arbitrário, precisamente para que a concessão de sudoers acima possa corresponder a um
comando/caminho exato em vez de precisar de curinga sobre o texto do script (o que seria frágil:
qualquer mudança futura no conteúdo do script invalidaria silenciosamente — ou ampliaria demais
silenciosamente — a concessão).

### Modo de criação de contêiner: segredo armazenado vs. aprovação de administrador

Se a criação de um contêiner é totalmente autoatendida ou exige o aval de um administrador é
**derivado** de se `nspawnmgr.ssh.password`/`SSH_PASSWORD` está configurado — não há uma alternância
separada:

- **Modo de segredo armazenado / autoatendido** (senha configurada, o padrão do `.deb`): a
  solicitação de "criar contêiner" de um proprietário provisiona imediata e automaticamente, o mesmo
  que antes deste recurso existir.
- **Modo de aprovação de administrador** (senha deixada em branco): um contêiner novo pousa em um
  estado `PENDING_APPROVAL` em vez de provisionar imediatamente. A página **Solicitações**
  (`/requests` — seu item de navegação lateral só aparece, para qualquer um, enquanto este modo
  estiver ativo) lista isso ao lado de qualquer solicitação pendente de conta de usuário dentro de
  contêiner em uma visão combinada. Um administrador vê e pode agir sobre todo item pendente de todo
  usuário; um não administrador só vê os próprios e pode **Negar** (move para um estado terminal
  `DENIED`, nenhum SSH jamais tentado) mas não **Aprovar** — aprovar precisa de uma senha de sudo,
  fornecida diretamente na tela, usada apenas para as etapas de criação daquele item específico,
  mantida em memória e zerada assim que essa execução termina, nunca persistida — deliberadamente
  pedida apenas de um administrador.

O login de transporte SSH e a senha de sudo compartilham o mesmo valor configurado, então deixar
`SSH_PASSWORD` em branco para selecionar o modo de aprovação de administrador de outra forma
deixaria a própria sessão SSH sem nada para se autenticar — mesmo para a camada NOPASSWD acima. **O
modo de aprovação de administrador, portanto, exige que
`nspawnmgr.ssh.private-key-path`/`SSH_PRIVATE_KEY_PATH` esteja definido**, para que a autenticação
de transporte SSH use uma chave em vez da senha (agora em branco). `setup-sudo-account.sh` gera essa
chave incondicionalmente independentemente do modo, então trocar de modo depois é realmente só
zerar/definir uma variável de ambiente e reiniciar — nada mais para configurar. O nspawnmgr falha ao
iniciar se nem uma senha nem uma chave privada estiverem configuradas de forma alguma
(`SshPropertiesValidator`), em vez de expor isso como uma falha de conexão confusa na primeira ação
de contêiner.

### Papéis de administrador e usuário

O papel de um usuário (`USER`/`ADMIN`) é necessário para controlar a página de aprovação acima. Dois
modos, novamente selecionados por se um valor de configuração está definido — desta vez
`nspawnmgr.auth.user-is-admin-json`:

- **Gerenciado pelo aplicativo** (padrão, em branco): o **primeiro usuário a fazer login** é
  automaticamente promovido a `ADMIN`; todos os demais têm `USER` como padrão. A partir daí,
  qualquer administrador pode promover ou rebaixar qualquer outro usuário em `/admin/users`. Papéis
  são fixos — nunca recalculados silenciosamente no login.
- **Gerenciado externamente** (`nspawnmgr.auth.user-is-admin-json` definido como um JsonPath no
  mesmo JSON de identidade que `auth.war` já retorna, ao lado de
  `user-id-json`/`user-username-json` etc.): o papel é recalculado do zero a partir desse JSON a
  cada login — tanto promoção quanto rebaixamento — e a página manual de conceder/revogar rejeita
  mudanças completamente, já que a fonte de identidade externa é autoritativa neste modo.

### Fronteira de confiança: comandos de provisionamento escritos em template

A camada que requer senha acima permite que `systemd-run` execute conteúdo como root dentro de um
contêiner. Esse conteúdo sempre vem de: uma string literal no próprio `ProvisioningService`, ou de
`Template.installSshCommand`/`installXrdpCommand`. Templates são editáveis através de
`/admin/templates`, controlados pelo papel ADMIN já existente em `/admin/**`, não um fluxo de
aprovação separado. Em outras palavras: **quem quer que detenha o papel ADMIN controla efetivamente
o que roda como root dentro de todo contêiner criado a partir de um template que edita.** No modo de
papel gerenciado pelo aplicativo, qualquer administrador atual pode conceder ADMIN a qualquer outra
pessoa em `/admin/users`, de forma autoatendida, sem etapa de aprovação adicional. Usuários comuns
(não administradores) logados ainda não conseguem alcançar isso de forma alguma — apenas
`GET /api/templates` (templates ativos, apenas resumo) é exposto fora de `/admin/**`.

### Fronteira de confiança: scripts de contêiner

O proprietário de um contêiner (ou qualquer um com quem esse contêiner tenha sido compartilhado —
veja "Compartilhado com" na página de detalhes do contêiner) pode definir scripts nomeados e
executá-los como root dentro desse mesmo contêiner, via `/containers/{id}/scripts`. Esta é uma forma
de confiança diferente da edição de template acima: o autor é o próprio proprietário/usuário
compartilhado do contêiner, e o script só roda dentro **desse único contêiner**, nunca no de outra
pessoa. Esses usuários já têm acesso interativo completo de shell root a esse contêiner exato
através da própria sessão SSH do Guacamole deles — executar um script salvo através deste recurso não
concede privilégio algum que eles já não tinham; é puramente uma conveniência (nomeado, reutilizável,
um clique em vez de redigitá-lo via SSH toda vez). É por isso que executar um script é NOPASSWD
(`/usr/bin/systemd-run --machine=* --pipe --quiet --wait /bin/sh -s`, de forma fixa, apenas esse
comando exato) diferente do conteúdo escrito em template acima, que roda dentro dos contêineres de
*outras* pessoas e é escrito por um administrador, não pelo próprio proprietário do contêiner.

**"Compartilhado com" concede mais do que acesso de sessão.** Compartilhar um contêiner concede ao
outro usuário uma sessão SSH/RDP do Guacamole *e* a capacidade de criar, editar, excluir, e executar
os scripts desse contêiner (acesso root completo, efetivamente — veja acima); não há uma alternância
separada para conceder um sem o outro. Se você compartilhou contêineres com pessoas puramente por
conveniência de desktop remoto, elas também têm direitos de script.

### Outras notas de configuração

- Esta conta também precisa de acesso de leitura/escrita para onde quer que você aponte
  `TEMPLATES_DIR`.
- Como isso é apenas-loopback por design, o nspawnmgr assume por padrão
  `strict-host-key-checking: false` para esta conexão. Só ative isso se algum dia apontar para um
  host que não seja localhost, e certifique-se de que a conta do Tomcat tenha um
  `~/.ssh/known_hosts` populado para o alvo primeiro.
- **Tudo isso assume que o nspawnmgr gerencia contêineres no mesmo host em que roda** (o único
  arranjo suportado pelo `.deb`). Apontar `nspawnmgr.ssh.host` para um host diferente em vez disso é
  um cenário configurado manualmente, não suportado pelas ferramentas: você precisaria repetir de
  forma independente a configuração de conta/sudoers/par-de-chaves desta seção nesse host remoto
  você mesmo.
- **O acesso SSH de `nspawnmgr_exec` é apenas-loopback por design** — não entregue suas credenciais
  a nada fora deste host. Se você quiser que um pipeline externo de CI/CD consiga
  instalar/atualizar templates de contêiner, use a conta `nspawnmgr_ci` separada e deliberadamente
  mais restrita em vez disso (veja "Instalando/atualizando templates a partir de um pipeline de
  CI/CD" acima) — ela é isolada em seu próprio arquivo sudoers com exatamente uma concessão de forma
  fixa, diferente do amplo acesso NOPASSWD/PASSWORD de `nspawnmgr_exec`, e foi pensada para ser
  alcançada pela rede.

Você conectará o nome de usuário/senha (ou chave privada) desta conta à própria configuração do
nspawnmgr como `nspawnmgr.ssh.*` (ou `SSH_USERNAME`/`SSH_PASSWORD`/`SSH_PRIVATE_KEY_PATH`) no
[§9](#9-configurando-o-nspawnmgr).

## 4. Banco de dados

MySQL, MariaDB, ou PostgreSQL — sem opção H2. H2 é usado internamente apenas pela suíte de testes do
dev-stack/CI (um banco de dados em memória, que desaparece no momento em que essa JVM para); nunca
foi um alvo de implantação suportado e não há caminho de código restante que possa selecioná-lo como
um. MySQL e MariaDB compartilham o mesmo driver JDBC, esquema, e local de migração Flyway —
escolher um em vez do outro só muda para qual nome de máquina o assistente aponta por padrão
(abaixo), não qual caminho de código roda. `spring.datasource.url` e
`spring.flyway.locations: classpath:db/migration/<vendor>` precisam concordar (veja `DB_VENDOR` na
referência de variáveis de ambiente — sempre `mysql` ou `postgresql`, nunca `mariadb`). O Flyway
executa migrações automaticamente na inicialização; `spring.jpa.hibernate.ddl-auto` é `validate`,
nunca `update` — o esquema é inteiramente responsabilidade do Flyway.

O banco de dados é **autoalojado**, da mesma forma que o próprio nspawnmgr é
([§1](#1-visão-geral-da-arquitetura)) — o assistente abaixo sempre provisiona um contêiner Debian
totalmente novo para executá-lo, em vez de pedir que você o aponte para um servidor já existente.

### Assistente de configuração de primeira inicialização

Você não precisa preparar banco de dados algum ou definir
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_VENDOR` você mesmo antes de iniciar o Tomcat pela primeira
vez — este assistente faz isso por você. Ele vive em seu próprio WAR (`ROOT.war`), implantado no
contexto raiz do Tomcat dentro da máquina autoalojada `nspawnmgr`
(`http://<host>:<porta encaminhada>/`, [§1](#1-visão-geral-da-arquitetura)) em vez de dentro do
próprio `nspawnmgr.war`: visitar `/` redireciona você diretamente para `/nspawnmgr/` assim que um
banco de dados funcional estiver configurado, ou mostra este assistente caso contrário. Acessar
`/nspawnmgr/` diretamente enquanto nenhum banco de dados ainda está configurado apenas redireciona
você de volta para `/` — o assistente é sempre o único lugar que decide em qual estado você está.

Escolha um **motor de banco de dados** (MySQL, MariaDB, ou PostgreSQL) e, opcionalmente, um **nome
de máquina de banco de dados** não padrão — o padrão é `mysqldb`, `mariadb`, ou `postgresdb` por
motor, editável. Preencha também um **nome de usuário e senha iniciais do nspawnmgr** — uma conta
Linux real, criada dentro da própria máquina autoalojada `nspawnmgr`, com a qual você fará login
assim que a configuração terminar (veja [§8](#8-auth-backend-de-login) para saber por que isso é
tudo o que o backend PAM do `auth.war` precisa, sem configuração extra).

Ao enviar, o assistente:

1. Provisiona a máquina de banco de dados (`nspawnmgr-bootstrap-db-machine.sh`, executado através da
   mesma conta SSH com capacidade de sudo que toda outra operação privilegiada neste aplicativo usa,
   veja [§3](#3-a-conta-ssh-com-capacidade-de-sudo)) — clona um template Debian, instala o motor
   escolhido (tanto MySQL quanto MariaDB instalam o próprio `mariadb-server` do Debian; não há um
   pacote Oracle MySQL separado no Debian), e aguarda uma unidade systemd de primeira inicialização
   dentro dessa máquina para criar os bancos de dados e usuários opinativos `nspawnmgr`/`guacamole`
   com senhas recém-geradas assim que o motor estiver genuinamente rodando (não tentado offline —
   ambos os motores realmente precisam rodar brevemente para executar `CREATE DATABASE`/
   `CREATE USER`).
2. Executa as próprias migrações Flyway do nspawnmgr, depois os scripts de esquema do Guacamole
   (toda instalação sempre começa de um banco de dados totalmente novo, então não há verificação de
   "já existe um esquema" para rodar aqui), e conecta a extensão `guacamole-auth-jdbc` do Guacamole
   para você (copia o JAR da extensão para `GUACAMOLE_HOME/extensions/` e escreve as propriedades
   `<vendor>-hostname`/`-port`/`-database`/`-username`/`-password` em
   `GUACAMOLE_HOME/guacamole.properties` — veja "GUACAMOLE_HOME e o backend de autenticação" do
   [§7](#7-guacamole) para o que isso serve). Se essa última etapa falhar por algum motivo, não é
   fatal — o próprio banco de dados do nspawnmgr (a coisa que realmente decide se este assistente
   continua aparecendo) já está funcionando naquele ponto, e a falha é apenas exibida como um aviso
   dizendo para você terminar essa etapa manualmente.
3. Cria a conta Linux inicial do nspawnmgr dentro da própria máquina autoalojada `nspawnmgr`, através
   da mesma conta com capacidade de sudo alcançando de volta essa máquina — o mesmo mecanismo que o
   `ProvisioningService` já usa para criar a própria conta de login de um contêiner gerenciado comum.
4. Salva as configurações de conexão funcionais do nspawnmgr em
   `/etc/nspawnmgr/db-config/db.properties` dentro da máquina `nspawnmgr` (pertencente a
   `tomcat:tomcat`, criado automaticamente por `nspawnmgr-bootstrap-app-machine.sh`).

A página de sucesso recarrega imediatamente os próprios contextos de `nspawnmgr.war` e do Guacamole
no lugar — nenhum botão para clicar, nenhum reinício do Tomcat necessário — tocando
`/opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml` e `guacamole.xml` (o mesmo wrapper
`nspawnmgr-write-file.sh` que outras operações privilegiadas usam, executado via o próprio helper SSH
sem Spring do assistente, já que não há contexto de aplicativo algum ainda neste ponto da
inicialização); a própria thread de auto-implantação em segundo plano do Tomcat percebe cada mudança
e reimplanta esse contexto no lugar. Para `/nspawnmgr`, isso reexecuta sua verificação de
alcançabilidade de inicialização e inicia o aplicativo real desta vez. O Guacamole precisa do mesmo
tratamento: em uma inicialização nova, seu próprio webapp inicia (e lê
`guacamole.properties`/carrega extensões, uma vez, naquele ponto) antes de um administrador ter tido
a chance de preencher este assistente de forma alguma — sem também reimplantá-lo aqui, o Guacamole
continuaria rodando sem extensão de autenticação apoiada em banco de dados carregada e rejeitaria
todo login, incluindo a conta `guacadmin` que a própria etapa de esquema deste assistente acabou de
criar. A página consulta `/nspawnmgr/` e leva você lá automaticamente assim que estiver de pé —
geralmente alguns segundos, não o reinício completo do Tomcat que isso costumava exigir.

O próprio assistente registra tanto a máquina `nspawnmgr` quanto sua máquina de banco de dados como
contêineres comuns e visíveis na própria lista de contêineres do nspawnmgr — pertencentes à conta
criada na etapa 3 acima, com uma descrição "Gerenciamento de máquina virtual"/"Servidor de banco de
dados" cada — diretamente em seu próprio trabalho de banco de dados logo após as migrações, sem
login necessário primeiro (veja ["Descobrindo máquinas criadas fora do
nspawnmgr"](#descobrindo-máquinas-criadas-fora-do-nspawnmgr) para o mesmo mecanismo de registro
subjacente, de outra forma acionado manualmente por um administrador). Quando você faz login pela
primeira vez (via essa mesma conta), você é simplesmente reconectado à identidade de administrador
que o assistente já criou ([§3](#papéis-de-administrador-e-usuário)) — ambas as máquinas já estão lá
esperando. Elas não ficam ocultas ou tratadas de forma especial depois; você pode conectar via SSH em
qualquer uma delas, compartilhá-las, excluí-las, como qualquer outro contêiner — embora excluir a
máquina `nspawnmgr` da qual você está atualmente rodando seja, evidentemente, uma má ideia.

**O próprio formulário do assistente não é autenticado e é alcançável de qualquer host.** Não há
banco de dados ainda, então não há tabela de usuários, então não há sistema de login algum para ele
ficar por trás — qualquer um que consiga alcançar esta porta antes do banco de dados estar
configurado pode configurá-lo. Restrinja o acesso de rede a esta porta você mesmo (regras de
firewall, mantendo-a fora de uma interface pública até que o §4 esteja concluído) se isso importar
para a sua implantação.

## 5. Instalando o nspawnmgr

Dois caminhos a partir daqui — escolha um. **A Opção A (o `.deb`) faz o §3 e a maior parte do §6 por
você**; a Opção B é o passo a passo totalmente manual a partir do §6 em diante. (Pacotes Arch Linux e
Fedora/RHEL também existem, com a mesma automação da Opção A — veja ["Instalando no Arch
Linux"](#instalando-no-arch-linux) e ["Instalando no Fedora/RHEL
(RPM)"](#instalando-no-fedorarhel-rpm) logo depois.) De qualquer forma, o §4 (banco de dados), a
configuração do `GUACAMOLE_HOME`/JDBC do Guacamole no §7, os valores de configuração no §9, e a
verificação no §10 continuam sendo sua responsabilidade — nenhum dos três pacotes automatiza mais do
que a *conta sudo* e *implantar os WARs no Tomcat*, não o próprio backend de armazenamento do
Guacamole ou as configurações em nível de aplicativo do nspawnmgr.

**O que você precisa para *construir* cada formato de pacote não é o mesmo que precisa para
*instalá-lo*** — vale a pena saber antes de escolher um caminho, especialmente se a máquina em que
você está construindo não for a mesma para a qual está implantando:

| Formato | Necessidades de construção | Necessidades de instalação | Construível de forma cruzada? |
|---|---|---|---|
| `.deb` (`packaging/nspawnmgr-deb/`) | JDK 21 + Maven (o plugin `jdeb` é Java puro) | `apt`, Debian/Ubuntu | **Sim** — construa em qualquer host com um JDK, incluindo Arch/Fedora/Windows/macOS |
| Arch (`packaging/nspawnmgr-arch/`) | JDK 21 + Maven, **mais `makepkg`/`base-devel`** | `pacman`, Arch Linux | **Não** — `makepkg` é uma ferramenta nativa do Arch sem equivalente multiplataforma; o host de construção precisa ser Arch em si (ou a imagem de contêiner `archlinux/devtools`) |
| RPM (`packaging/nspawnmgr-rpm/`) | JDK 21 + Maven, **mais `rpm-build`** | `dnf`, Fedora/RHEL | **Não** — apesar da reputação do `rpm-maven-plugin`, ele genuinamente invoca um binário `rpmbuild` real; confirmado ao vivo que falha completamente em um host de construção não-RPM (ex.: Windows), sem equivalente multiplataforma, mesma história do `makepkg` do Arch |

Se você não tiver uma máquina Arch ou Fedora sobrando para construir isso,
`packaging/ci/arch-runner/bootstrap-arch-runner.sh` e
`packaging/ci/fedora-runner/bootstrap-fedora-runner.sh` mostram uma forma de obter qualquer uma sem
dual-boot ou hardware físico: ambos preparam um rootfs real em um simples contêiner `systemd-nspawn`
(não uma imagem Docker/Podman — o nspawn acabou sendo o mais simples aqui, já que compartilha o
namespace de rede do host por padrão em vez de precisar de sua própria ponte só para CI). Os jobs
`arch-package` e `rpm-package` do `.gitea/workflows/build.yml` mostram os comandos de construção
exatos que rodam assim que cada contêiner existe (instalar o JDK/Maven/ferramentas de empacotamento
nativas, depois `BUILD_ARCH_PKG=1`/`BUILD_RPM=1 tools/scripts/build-all.sh`, como mostrado abaixo).

### Opção A: o pacote `.deb` (recomendado)

Apenas Debian/Ubuntu para o **host** — as máquinas autoalojadas `nspawnmgr`/banco de dados que ele
cria são sempre Debian independentemente, conforme [§1](#1-visão-geral-da-arquitetura). Cuida do §3
(a conta com capacidade de sudo, sudoers, par de chaves SSH) e cria+inicia a máquina autoalojada
`nspawnmgr` com o Tomcat, os quatro WARs, e o `guacd` já instalados dentro dela — o *resto* do §6
não é dispensável, porém: "Habilitando HTTPS" e "Usando uma porta diferente" em particular ainda
valem a leitura (veja "O que ainda é manual depois disso" abaixo), apenas aplicados dentro dessa
máquina agora, em vez de no host. Continue para o §7 assim que estiver instalado.

**Obtenha um `.deb`**, seja construindo um você mesmo:

```bash
mvn -DskipTests install                          # root -> target/nspawnmgr.war (installed, not just packaged - the next module needs it)
mvn -f auth/pom.xml -DskipTests package          # -> auth/target/auth.war
mvn -f packaging/nspawnmgr-deb/pom.xml package   # -> packaging/nspawnmgr-deb/target/nspawnmgr_*.deb
```

(ou `BUILD_DEB=1 tools/scripts/build-all.sh`, que faz as mesmas três etapas — essa variável de
ambiente existe porque construir um `.deb` precisa de acesso à rede para buscar o plugin Maven
`jdeb` no primeiro uso, o que uma construção de desenvolvimento simples não deveria ser forçada a
fazer), seja instalando um já construído de onde quer que sua equipe o publique — o próprio CI deste
repositório (o job `publish-deb` do `.gitea/workflows/build.yml`) publica toda construção
bem-sucedida em um registro de pacotes Debian do Gitea como referência funcional se você quiser
configurar a mesma coisa para seu próprio fork/instância (precisa de um segredo de Actions do
repositório `PACKAGE_REGISTRY_TOKEN`, um token de acesso do Gitea com escopo de escrita de pacotes —
veja o próprio comentário desse job no arquivo do workflow).

**Instale-o:**

```bash
sudo apt install ./nspawnmgr_0.4.0_all.deb   # pulls in openssh-server, openssl, dnsmasq, systemd-container - not a JRE, not tomcat9
```

Nem `tomcat9` nem `guacd`/`guacamole-tomcat` estão em `Depends:` deste pacote — a própria
disponibilidade do `tomcat9` no apt varia o suficiente por release, e `guacd`/`guacamole-tomcat` não
são empacotados em release atual algum (veja a própria nota de
`packaging/nspawnmgr-deb/debian/control`). `tomcat9`, `guacd`, e `guacamole.war` são todos
empacotados junto em vez disso e não precisam de nada de você (veja §6 e §7) — a única etapa manual
restante no §7 é a extensão de autenticação apoiada em banco de dados, já que ela genuinamente
precisa de credenciais que só você tem.

**O que acabou de acontecer, automaticamente** (veja `packaging/nspawnmgr-deb/debian/postinst` e
`nspawnmgr-bootstrap-app-machine.sh` para os scripts exatos):

- Uma conta de sistema `nspawnmgr_exec` foi criada no **host**; uma senha aleatória foi gerada para
  ela (apenas na primeira instalação — intocada em atualização) e escrita em
  `/etc/nspawnmgr/nspawnmgr.env` (esta é a senha de sudo "segredo armazenado" do §3 — veja o §3 para
  o que isso significa e como trocar para o modo de aprovação de administrador em vez disso); um par
  de chaves SSH foi gerado e instalado no `authorized_keys` dessa conta independentemente do modo. A
  divisão de camada NOPASSWD/senha do §3 → `/etc/sudoers.d/nspawnmgr_exec`, validada com
  `visudo -cf` antes de ser confiada.
- A ponte compartilhada (`nspawnbr0`) e o dnsmasq foram configurados no host, o mesmo que para
  qualquer outro contêiner gerenciado — veja "Resolvendo contêineres por nome" acima.
- `debian-minimal` foi preparado (o mesmo tarball que "Configurar debian-minimal" em
  `/admin/templates` produziria) e clonado em uma máquina nova chamada `nspawnmgr`.
- Ainda apenas como um rootfs extraído, ainda não inicializado: um JRE, o tarball do Apache Tomcat
  9.0.120 empacotado, os quatro WARs (`nspawnmgr.war`/`auth.war`/`guacamole.war`/`ROOT.war`), e o
  próprio bundle autocontido do `guacd` (OpenSSL 3.x próprio, FFmpeg mínimo, FreeRDP2, libssh2) foram
  instalados diretamente no próprio sistema de arquivos dessa máquina — usuários de sistema
  `tomcat`/`guacd` criados dentro dela, os webapps `manager`/`host-manager`/`examples`/`docs`
  removidos, `GUACAMOLE_HOME` semeado com um `guacamole.properties` mínimo apontando para o próprio
  `guacd` dessa mesma máquina, e `guacamole-auth-jdbc` mais ambos os jars de driver JDBC extraídos
  para dentro (tudo sem necessidade de acesso à rede — tudo empacotado, nada baixado).
- Uma cópia reescrita de `/etc/nspawnmgr/nspawnmgr.env` foi escrita nessa máquina (`SSH_HOST` e
  `HOST_PUBLIC_ADDRESS` reapontados para o próprio endereço da `nspawnbr0` em vez de `127.0.0.1`,
  para que o nspawnmgr consiga alcançar de volta a conta `nspawnmgr_exec` do host assim que
  inicializar), junto com uma cópia da chave privada SSH.
- Uma porta livre do host foi escolhida (`8080` primeiro, incrementando além de qualquer uma já em
  uso — impressa durante a instalação) e encaminhada para a própria porta `:8080` dessa máquina via
  uma linha `Port=` em seu arquivo `.nspawn`, então `http://<este host>:<essa porta>/` alcança o
  nspawnmgr exatamente como uma instalação não autoalojada sempre alcançou.
- A máquina foi iniciada. O Tomcat dentro dela sobe servindo o assistente de banco de dados de
  primeira inicialização do `ROOT.war` (§4) — não há banco de dados algum configurado ainda neste
  ponto, o mesmo que antes, apenas alcançável em um endereço subjacente diferente agora.

**Confirme que chegou corretamente:**

```bash
sudo machinectl list                             # should show "nspawnmgr" running
sudo visudo -cf /etc/sudoers.d/nspawnmgr_exec    # should print "parsed OK"
curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port shown during install>/
```

Nada relacionado ao Tomcat roda mais no próprio host — não procure por `tomcat9.service` ou
`/opt/tomcat9` ali; ambos agora vivem dentro da máquina `nspawnmgr` (`sudo machinectl shell
nspawnmgr` para olhar ao redor dentro dela, ou use o próprio acesso SSH do nspawnmgr a ela assim
que você estiver logado — veja a observação do §4 sobre ela aparecer na lista de contêineres). O
`.deb` nunca escreve `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` no `nspawnmgr.env` dessa máquina — apenas
as configurações de sudo/nome de host — então a verificação com curl acima:

- **`200`** — nenhum banco de dados funcional ainda, então você está olhando para o assistente de
  configuração de primeira inicialização descrito em "Assistente de configuração de primeira
  inicialização" do §4. Este é o estado normal logo após uma instalação nova do `.deb`; preencha o
  assistente para continuar.
- **`302`** (redirecionamento para `/nspawnmgr/`) — um banco de dados funcional já está configurado.
  Siga-o e espere outro `302` (para a página de login) se o aplicativo real iniciou normalmente, ou
  um `404` se não iniciou: o contexto Spring do nspawnmgr falhou ao iniciar. Verifique
  `sudo machinectl shell nspawnmgr journalctl -u tomcat9` antes de assumir que o próprio pacote está
  quebrado (a própria página "Ver log" da interface web do nspawnmgr não vai ajudar aqui — o próprio
  nspawnmgr nunca chegou longe o suficiente para inicializar); geralmente é um valor
  faltando/incorreto no próprio `/etc/nspawnmgr/nspawnmgr.env` dessa máquina (o §9 cobre o que cada
  configuração significa).

**O que ainda é manual depois disso**: apontar o assistente de primeira inicialização (§4) para um
servidor MySQL/PostgreSQL — ele cria tanto o banco de dados `nspawnmgr` quanto o `guacamole`, executa
o esquema de ambos os aplicativos, e conecta a extensão `guacamole-auth-jdbc` do Guacamole para você,
mas você ainda precisa executá-lo uma vez e ainda precisa criar a conta de administrador do Guacamole
depois; pelo menos um template de contêiner ("Templates de contêiner" do §2 — nada pode ser criado
até que um exista; uma instalação nova começa com zero, então o botão de um clique "Configurar
debian-minimal" de `/admin/templates` está disponível imediatamente); revisar/ajustar o resto de
`/etc/nspawnmgr/nspawnmgr.env` contra o §9 (URL base do Guacamole, etc. — o arquivo gerado preenche
a credencial de sudo, `APP_SECRET_KEY`, e `USER_ID_URL`/`AUTH_LOGIN_URL` apontados para o próprio
`auth.war` empacotado deste host, mas não configuração de aplicativo sem um padrão auto-gerado
sensato), habilitar HTTPS ("Habilitando HTTPS" do §6 — o `.deb` deixa o Tomcat em HTTP simples por
padrão, o mesmo que o caminho manual; fortemente recomendado se você estiver usando o modo de
aprovação de administrador, conforme essa seção), e verificação (§10).

O `postrm` deliberadamente nunca exclui `nspawnmgr_exec` ou `/etc/nspawnmgr` na remoção/purga do
pacote — essa conta é a única credencial através da qual seus contêineres ficam alcançáveis.

**Para atualizar uma instalação existente para uma nova construção do pacote** (uma correção de bug,
não uma instalação nova): `sudo /usr/lib/nspawnmgr/upgrade-nspawnmgr.sh <path-to-the-new-package-file>`.
Um simples `apt install`/`dnf install`/`pacman -U` — ou mesmo `apt install --reinstall` — não basta
por si só: esses podem silenciosamente não fazer nada se a string de versão instalada registrada não
mudou, o que importa já que toda construção dentro de um ciclo de desenvolvimento republica sob a
mesma versão fixa. Este script instala o arquivo de pacote fornecido diretamente em vez disso
(sempre aplica seu conteúdo, independentemente da versão registrada), o que por sua vez
redispara a própria pós-instalação do pacote — e isso sempre chama
`nspawnmgr-bootstrap-app-machine.sh`, que reconcilia completamente o conteúdo da máquina autoalojada
`nspawnmgr` a cada chamada, não apenas na primeira instalação: os quatro WARs empacotados, o próprio
bundle e serviço do `guacd`, a unidade de serviço do Tomcat, e o arquivo de credencial de
conexão-de-volta SSH são todos atualizados, e a máquina é parada/reiniciada ao redor disso para que
nada seja sobrescrito enquanto ainda em uso. Sua porta encaminhada pelo host já existente é
preservada durante a atualização, não escolhida novamente. Não destrutivo — `/var/lib/machines`
(todo *outro* contêiner) e ambos os bancos de dados são deixados completamente em paz; o clone do
rootfs base e as contas de sistema `tomcat`/`guacd` dentro da máquina também são deixadas em paz
(retocar essas poderia estragar uma personalização real de administrador, ou falhar completamente em
uma segunda execução) — um aumento de *versão* do Tomcat especificamente ainda precisa de uma
reinstalação completa, o mesmo que antes.

**Para remover tudo isso de qualquer forma** (máquinas de teste, começando do zero — não algo para
rodar em uma implantação real sem pensar nisso primeiro, já que exclui as credenciais de sudo/SSH
através das quais seus contêineres permanecem alcançáveis):
`sudo /usr/lib/nspawnmgr/uninstall-nspawnmgr.sh`. Além do que `apt purge` já faz, também remove
`/opt/tomcat9`, `/etc/nspawnmgr`, `/etc/guacamole`, `/var/lib/nspawnmgr/templates`
(`TEMPLATES_DIR` — tarballs de template, incluindo qualquer coisa que o botão "Configurar
debian-minimal" tenha baixado; um arquivo de template restante sobrevivendo a uma purga é
exatamente o que faz a verificação "não deve já existir" desse botão falhar em uma reinstalação
posterior), as contas de sistema `tomcat`/`nspawnmgr_exec`, e quaisquer [configurações de
inicialização de máquina](#iniciando-automaticamente-quando-o-host-inicializa) que o nspawnmgr
configurou (habilitação de unidade de início automático, o drop-in de exigir-outra-máquina) — isso é
puro estado de arquivo de unidade systemd chaveado apenas pelo nome da máquina, intocado por
`apt purge` ou mesmo removendo os próprios contêineres, e um drop-in `Requires=` obsoleto
sobrevivendo de uma instalação anterior é suficiente para quebrar uma reinstalação nova completamente
(`machinectl start nspawnmgr` falhando com "A dependency job for
systemd-nspawn@nspawnmgr.service failed." porque a unidade que ele exigia não existia mais) — tudo
aqui é o que o `postrm` deliberadamente deixa para trás, para os casos em que esse conservadorismo
não é o que você quer. Por padrão, ele ainda **não** toca no próprio banco de dados do nspawnmgr, no
próprio banco de dados do Guacamole, ou em `/var/lib/machines` (seus contêineres reais) — apenas a
camada de gestão ao redor deles (mais os templates usados para criá-los) — mas separadamente pergunta
(seu próprio prompt s/n cada, nunca implícito por `--yes`) se também deve descartar os bancos de
dados `nspawnmgr`/`guacamole` e seus usuários de BD (só suportado quando `DB_URL` aponta para
`localhost`/`127.0.0.1`, lido de `db.properties`/`nspawnmgr.env` antes que esses arquivos sejam
removidos) e se deve remover todo contêiner atualmente registrado com `machinectl`. Útil para
resetar rapidamente um host de teste real entre iterações, já que essas duas etapas são perda real
de dados.

### Instalando no Arch Linux

Construção e instalação ambas verificadas ao vivo em sistemas reais da família Arch: `makepkg -f`
contra este `PKGBUILD` exato (o contêiner systemd-nspawn `arch-runner` na acer — veja
`packaging/ci/arch-runner/`) produz um `nspawnmgr-0.3.0-1-any.pkg.tar.zst` real via o job
`arch-package` do `.gitea/workflows/build.yml`, e os próprios hooks `pacman -U` +
`nspawnmgr.install` do pacote resultante foram exercitados repetidamente em um sistema SteamOS real
(baseado em Arch, compatível com `pacman` assim que `steamos-readonly disable` é executado) —
instalações novas, ciclos de desinstalação/reinstalação, e atualizações no lugar via
`upgrade-nspawnmgr.sh` foram todas confirmadas funcionando, incluindo a máquina autoalojada subindo
com uma concessão de rede real e a interface web respondendo corretamente. Um pacote **separado**,
`packaging/nspawnmgr-steamos/`, existe especificamente para o SteamOS (veja seus próprios
`provides`/`conflicts` contra este — instale exatamente um dos dois, nunca ambos) já que a pequena
partição raiz do SteamOS precisa de armazenamento realocado sob `/home`; este pacote Arch simples é
o que um host Arch não-SteamOS deveria instalar em vez disso. Esse caminho não-SteamOS — instalar
este pacote exato em um Arch genuinamente vanilla (em oposição ao SteamOS, que compartilha a mesma
mecânica subjacente de `pacman`/`systemd` mas não é idêntico) — ainda não foi testado diretamente;
relate o que quebra se você tentar.

`packaging/nspawnmgr-arch/` (um `PKGBUILD` + `nspawnmgr.install`, não um módulo Maven — não existe
plugin de empacotamento Arch nativo do Maven) é, de resto, a mesma arquitetura autoalojada da Opção
A acima, apenas um formato de pacote diferente: mesma configuração de conta/sudoers/ponte/dnsmasq
`nspawnmgr_exec`, mesma máquina autoalojada `nspawnmgr` (ainda Debian-minimal independentemente da
própria distro deste host — veja [§1](#1-visão-geral-da-arquitetura) — um host Arch não muda o que a
própria *máquina de aplicativo* autoalojada roda, apenas o que o *host puro* em si precisa), mesmos
"O que acabou de acontecer", "Confirme que chegou corretamente", e "O que ainda é manual depois
disso" que a Opção A — leia-os acima, eles se aplicam aqui sem mudanças. As diferenças são estreitas:

- **Dependências**: `openssh`, `openssl`, `dnsmasq` — nenhum JRE, nenhum equivalente a
  `apache2-utils` (ambos instalam *dentro* da máquina de aplicativo autoalojada, não necessários no
  host puro de forma alguma — veja `nspawnmgr-bootstrap-app-machine.sh`), nenhum equivalente a
  `systemd-container` (`machinectl`/`systemd-nspawn` já vêm no próprio pacote `systemd` base do
  Arch).
- **Nenhuma etapa de firewall**: diferente da exceção DHCP do `ufw` do `.deb`, o Arch não vem com
  firewall algum habilitado por padrão, então não há nada a contornar. Se você configurou
  `nftables`/`iptables`/`ufw` você mesmo, certifique-se de que UDP/67 de entrada na `nspawnbr0` esteja
  permitido (o mesmo requisito para o qual a própria etapa de `ufw` do `.deb` existe).
- **A remoção permanece conservadora por padrão**: `pacman -R`/`-Rns` não dá a mesma distinção
  purga-vs-remoção que `dpkg`/`apt` dá, então o `post_remove()` de `nspawnmgr.install`
  deliberadamente faz tão pouco quanto o próprio comportamento padrão (não-purga) do `postrm` — o
  mesmo script `uninstall-nspawnmgr.sh` do `.deb` lida com a limpeza completa, ainda instalado no
  mesmo caminho.

Construa e instale:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_ARCH_PKG=1 tools/scripts/build-all.sh   # needs `makepkg` on PATH - a real Arch host, or the
                                               # archlinux/devtools container image

sudo pacman -U packaging/nspawnmgr-arch/nspawnmgr-0.4.0-1-any.pkg.tar.zst
```

### Instalando no Fedora/RHEL (RPM)

Construção e instalação ambas verificadas ao vivo em um host Fedora 43 real sob SELinux
`Enforcing` (o contêiner systemd-nspawn `fedora-runner` na acer para construção — veja
`packaging/ci/fedora-runner/` — e um `fedora-test-vm` QEMU convidado separado para verificação de
instalação): o fluxo real de ponta a ponta (assistente de configuração de BD, login, criação de
contêiner, e atualizações repetidas no lugar via `upgrade-nspawnmgr.sh`) foi confirmado funcionando,
incluindo sob SELinux Enforcing especificamente.

`packaging/nspawnmgr-rpm/` (um módulo Maven real — o `rpm-maven-plugin` genuinamente invoca
`rpmbuild`, não é Java puro apesar das aparências) é, de resto, a mesma arquitetura autoalojada da
Opção A acima — mesma configuração de conta/sudoers/ponte/dnsmasq `nspawnmgr_exec`, mesma máquina
autoalojada `nspawnmgr` (ainda Debian-minimal independentemente da própria distro deste host),
mesmos "O que acabou de acontecer", "Confirme que chegou corretamente", e "O que ainda é manual
depois disso" que a Opção A. As diferenças são estreitas:

- **Dependências**: `openssh-server`, `openssl`, `dnsmasq`, `systemd-container`, e `iptables-nft` —
  o pacote do Fedora apoiado em nftables que de fato fornece `/usr/bin/iptables` (o nome de pacote
  `iptables` simples não existe no Fedora; a alternância de internet de saída por contêiner precisa
  de um binário `iptables` real independentemente do backend).
- **Exceção do firewalld**: o Fedora vem com `firewalld` ativo por padrão. A instalação adiciona a
  `nspawnbr0` à zona `trusted` do firewalld e recarrega — sem isso, a política de zona padrão do
  firewalld bloqueia silenciosamente concessões DHCP para contêineres, a mesma forma de falha da
  própria exceção do `firewalld` do SteamOS (abaixo).
- **Módulo de política SELinux**: sob o modo `Enforcing`, `systemd_machined_t` precisa de um pequeno
  módulo de política personalizado (`nspawnmgr_machined_cgroup.te`, compilado a partir da fonte no
  momento da instalação via `checkmodule`/`semodule_package`/`semodule -i` em vez de enviado como um
  `.pp` pré-compilado, para que corresponda a qualquer versão de política de fato em execução)
  concedendo `watch` em arquivos `cgroup_t` — uma lacuna geral de política SELinux em qualquer host
  Fedora Enforcing padrão, não específica do nspawnmgr, que de outra forma quebra todo início de
  contêiner `machinectl`/`systemd-nspawn` com "Failed to register machine: Access denied."
- **A remoção permanece conservadora por padrão**, mesma postura e mesmo script
  `uninstall-nspawnmgr.sh` que os outros dois formatos de pacote.

Uma ressalva de topologia de ambiente, não um bug de código: o nome de host autodetectado de
`AUTH_LOGIN_URL` precisa ser resolvível de onde quer que o navegador de fato se conecte (uma escolha
de design deliberada — veja [§9](#9-configurando-o-nspawnmgr) — que evita um loop de login com
escopo de cookie ainda pior). Isso pode causar problemas especificamente ao testar através de uma
topologia NAT/túnel/encaminhamento-de-porta, em vez de um nome de host real diretamente alcançável;
ajuste `AUTH_LOGIN_URL` manualmente nesse caso.

Construa e instale:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_RPM=1 tools/scripts/build-all.sh   # needs a real `rpmbuild` binary (`rpm-build` package) -
                                          # a real Fedora/RHEL host, no cross-platform equivalent

sudo dnf install ./packaging/nspawnmgr-rpm/target/rpm/noarch/nspawnmgr-0.4.0-1.noarch.rpm
```

### Opção B: construir a partir do código-fonte, implantar manualmente

**Este caminho implanta o Tomcat diretamente no host em que você está trabalhando — ele não
autoaloja o nspawnmgr em sua própria máquina da forma que a Opção A faz.** Tudo bem; o
autoalojamento é uma escolha opinativa que o `postinst` do `.deb` faz, não um requisito rígido — uma
implantação manualmente construída, com Tomcat no host, ainda é totalmente suportada, é apenas a
topologia mais antiga/simples. Se você quiser o modelo autoalojado sem o `.deb`, o caminho mais
direto é ler `nspawnmgr-bootstrap-app-machine.sh` e fazer manualmente o que ele faz (preparar um
template, cloná-lo, instalar um JRE/Tomcat/os WARs no rootfs desse contêiner, etc.) em vez de seguir
o §6 abaixo, que implanta o Tomcat no próprio host, o mesmo que sempre fez.

A partir da raiz do repositório:

```bash
mvn -DskipTests package                # -> target/nspawnmgr.war
mvn -f auth/pom.xml -DskipTests package  # -> auth/target/auth.war
```

(`tools/scripts/build-all.sh` faz ambos, mais os módulos simulados apenas de desenvolvimento — as
simulações não são necessárias para uma implantação real.) Continue para o §6 para a configuração
manual de Tomcat/conta/sudoers que o `.deb` teria feito por você de outra forma.

O `postinst` do `.deb` também cria `/etc/nspawnmgr/auth-live/`, pertencente a `tomcat:tomcat` modo
`750` — o arquivo compartilhado no qual `/admin/settings` escreve a configuração ao vivo do
auth.war (veja [§9](#9-configurando-o-nspawnmgr)). Uma implantação manual precisa do mesmo, assim
que o usuário `tomcat` do Tomcat existir (§6):

```bash
sudo mkdir -p /etc/nspawnmgr/auth-live
sudo chown tomcat:tomcat /etc/nspawnmgr/auth-live
sudo chmod 750 /etc/nspawnmgr/auth-live
```

## 6. Tomcat 9 (nspawnmgr + Guacamole + auth)

**Esta seção descreve implantar o Tomcat diretamente no host** — a forma que uma instalação manual
(§5 Opção B) assume. Se você instalou via pacote `.deb`/Arch/RPM (§5 Opção A), o Tomcat não está no
host de forma alguma — está dentro da máquina autoalojada `nspawnmgr`, já configurado por
`nspawnmgr-bootstrap-app-machine.sh`, e nada desta seção se aplica; vá direto para o §7.

O próprio webapp oficial do Guacamole ainda tem como alvo `javax.servlet`, então ele e o nspawnmgr
são implantados lado a lado na **mesma instância do Tomcat 9**.

**Não é uma dependência do apt.** Como o `guacd` (§7), a própria disponibilidade do pacote apt
`tomcat9` varia o suficiente entre releases do Debian/Ubuntu/Mint que este projeto empacota uma
distribuição binária vanilla e upstream do Apache Tomcat em vez de depender dele — uma release de
patch atual (9.0.120), não o que quer que um arquivo apt aconteça a carregar, e este pacote é dono
da instância inteira ele mesmo (`/opt/tomcat9`, seu próprio usuário de sistema `tomcat`, seu próprio
`tomcat9.service`). **Se uma versão anterior deste pacote (que dependia do `tomcat9` do apt) já
estiver instalada, remova o `tomcat9` desse pacote primeiro** — duas instâncias do Tomcat tentando
ambas se vincular a `:8080` vão falhar.

Caso contrário (Opção B), extraia o mesmo tarball empacotado que o `.deb` envia —
`packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz` em um checkout do repositório — em vez
de baixar uma cópia nova você mesmo, para que uma instalação manual corresponda à release de patch
exata contra a qual este projeto é testado:

```bash
sudo mkdir -p /opt/tomcat9
sudo tar -xzf packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz -C /opt/tomcat9 --strip-components=1
sudo chmod +x /opt/tomcat9/bin/*.sh
```

Execute o Tomcat como seu próprio usuário de sistema sem privilégios, sem sudo (nunca root, e
deliberadamente não a mesma conta do [§3](#3-a-conta-ssh-com-capacidade-de-sudo)):

```bash
sudo useradd -r -M -d /opt/tomcat9 -s /usr/sbin/nologin tomcat
sudo chown -R tomcat:tomcat /opt/tomcat9
```

**Se você fez o [§3](#3-a-conta-ssh-com-capacidade-de-sudo) antes disto** (a ordem documentada),
volte e torne o par de chaves SSH que ele gerou (`SSH_PRIVATE_KEY_PATH`, padrão
`/etc/nspawnmgr/ssh_id_ed25519`) legível por este usuário `tomcat` agora que ele existe —
`SshRemoteExecutor` abre esse arquivo diretamente de dentro do próprio processo do Tomcat a cada
operação privilegiada, e a chave é criada `root:root` modo `600` (sem acesso de grupo algum) já que
`tomcat` ainda não existe naquele ponto:

```bash
sudo chown root:tomcat /etc/nspawnmgr/ssh_id_ed25519
sudo chmod 640 /etc/nspawnmgr/ssh_id_ed25519
```

Pular isso deixa toda operação privilegiada falhando com "Failed to establish SSH connection to
127.0.0.1:22" — um problema de permissões, não de conectividade, apesar da redação.

O tarball upstream empacota webapps `manager`/`host-manager`/`examples`/`docs` que o próprio pacote
`tomcat9` do Debian separa em subpacotes distintos, não instalados por padrão; o `postinst` do
`.deb` remove esses na primeira instalação pelo mesmo motivo — superfície de ataque real e evitável
se deixada implantada sem configuração — vale a pena fazer manualmente aqui também:

```bash
sudo rm -rf /opt/tomcat9/webapps/manager /opt/tomcat9/webapps/host-manager \
       /opt/tomcat9/webapps/examples /opt/tomcat9/webapps/docs
```

Implante o nspawnmgr:

```bash
sudo cp target/nspawnmgr.war /opt/tomcat9/webapps/nspawnmgr.war
```

O nspawnmgr, o Guacamole, e o `auth` (§8) cada um tem seu próprio caminho de contexto abaixo —
nenhum deles pode reivindicar a raiz do servidor sem abrir mão desse caminho — então coloque uma
pequena página estática de redirecionamento para o `http://<hostname>:8080/` puro, usando o próprio
`site/root-index/index.html` deste repositório como referência (redireciona para `/nspawnmgr/`):

```bash
sudo mkdir -p /opt/tomcat9/webapps/ROOT
sudo cp site/root-index/index.html /opt/tomcat9/webapps/ROOT/index.html
sudo chown -R tomcat:tomcat /opt/tomcat9/webapps/ROOT
```

Defina `SPRING_PROFILES_ACTIVE=prod` (mais toda outra variável de ambiente do
[§9](#9-configurando-o-nspawnmgr)) em o que quer que envolva a inicialização do Tomcat (o
`Environment=`/`EnvironmentFile=` de uma unidade systemd, ou `bin/setenv.sh` sob `CATALINA_OPTS` —
coloque entre aspas todo valor `-D` que contiver um `;`, já que `catalina.sh` reavalia
`$CATALINA_OPTS` como uma linha de comando shell e um `;` sem escape é interpretado como um
separador de comando, truncando silenciosamente o lançamento). Sem um perfil ativo, o nspawnmgr
assume `dev` por padrão (H2 em memória, executores simulados) — não o que você quer aqui.

Configure-o como um serviço systemd para que sobreviva a reinicializações, ex.:
`/etc/systemd/system/tomcat9.service` (a mesma unidade que o `.deb` instala —
`packaging/nspawnmgr-deb/tomcat9.service` em um checkout do repositório é uma referência pronta):

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

`Type=simple` com `catalina.sh run` (primeiro plano) em vez de `Type=forking` com
`startup.sh`/`shutdown.sh` — o systemd supervisiona a JVM diretamente desta forma, então uma falha é
detectada e `Restart=on-failure` de fato dispara; uma unidade forking só sabe se o próprio *script
wrapper* saiu, não se o próprio Tomcat ainda está vivo.

```bash
sudo systemctl enable --now tomcat9
```

### Usando uma porta diferente

O Tomcat escuta em `8080` por padrão (o `<Connector port="8080" .../>` de `conf/server.xml`). Para
mudar isso, edite esse atributo `port` diretamente:

```bash
sudo sed -i 's/port="8080"/port="8180"/' /opt/tomcat9/conf/server.xml
```

Ou use a seção **Tomcat** em `/admin/settings` em vez de editar `server.xml` manualmente — ela
lê/escreve o mesmo arquivo (localizado via a propriedade de sistema JVM `catalina.base` que o
próprio script de inicialização do Tomcat sempre define, então encontra o `server.xml` certo quer
você esteja rodando o `tomcat9` empacotado em Debian pelo `.deb` ou um extraído manualmente sob
`/opt/tomcat9`), passando pela mesma conta SSH com capacidade de sudo e script wrapper
`nspawnmgr-write-file.sh` que toda outra operação privilegiada já usa — nenhuma concessão de sudoers
nova necessária. É o **próprio arquivo que é autoritativo**, não uma cópia no banco de dados: a
página sempre mostra e edita o que quer que esteja de fato no disco, então editar `server.xml`
manualmente (como acima) e usar a página de configurações são totalmente intercambiáveis — nenhum
dos dois fica desatualizado em relação ao outro.

Todo outro `:8080` neste guia (e na sua própria configuração —
`nspawnmgr.auth.user-id-url`/`AUTH_LOGIN_URL`, `nspawnmgr.guacamole.base-url`, e qualquer URL que
você diga aos usuários para visitar) precisa ser atualizado para corresponder — nada deriva a porta
automaticamente de `server.xml`, seja qual for a forma como você a muda. Em `/admin/settings`, isso
é geralmente um clique por campo: cada um desses campos de URL tem um botão "Atualizar
hostname/porta/protocolo" que o reescreve a partir do próprio estado atual de porta/HTTPS da seção
Tomcat mais `host.external-hostname` (§8) — sem necessidade de editar manualmente a porta de cada
URL separadamente. Se você estiver atrás de um firewall, certifique-se de que a nova porta esteja
aberta em vez de `8080`. De qualquer forma, a mudança só tem efeito após um reinício — use o botão
Reiniciar Tomcat em `/admin/settings` (veja acima) ou `sudo systemctl restart tomcat9` você mesmo.

### Habilitando HTTPS

Duas opções, em ordem de como a maioria das implantações reais de fato fazem isso:

1. **Terminar o TLS com um proxy reverso** (nginx, Apache, Caddy, um balanceador de carga de nuvem)
   na frente do Tomcat, que continua escutando em HTTP simples em `127.0.0.1:8080` apenas
   (vincule-o a loopback em `<Connector address="127.0.0.1" .../>` de `server.xml` para que não
   fique diretamente alcançável). Este geralmente é o caminho mais fácil para renovação de
   certificado (ex.: Certbot/Let's Encrypt) já que fica desacoplado do próprio formato de keystore
   do Tomcat. Aponte toda URL `nspawnmgr.*`/`AUTH_LOGIN_URL` neste guia para
   `https://<hostname>/...` (seja qual for a porta em que o proxy escuta) em vez de
   `http://<hostname>:8080/...` — o proxy, não o Tomcat, é o que os requisitos de
   hostname/cookie no [§8](#nome-de-host-e-o-cookie-de-sessão-compartilhado) de fato se aplicam.

2. **Configurar um conector SSL do Tomcat diretamente**, se preferir não rodar um proxy reverso.
   Desde o Tomcat 8.5/9, o elemento `<Certificate>` de `<SSLHostConfig>` aceita um certificado/chave
   PEM diretamente (`certificateFile`/`certificateKeyFile`/`certificateChainFile`) — nenhuma
   conversão de keystore Java necessária, o que importa porque é exatamente o formato que os
   clientes Let's Encrypt/ACME (ex.: Certbot) entregam a você (`fullchain.pem`/`privkey.pem`).
   Aponte o Certbot para este host (`certbot certonly --standalone -d nspawnmgr.example.com`, ou
   qualquer plugin que sirva para sua configuração) e adicione um conector ao `server.xml`:

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

   O usuário de sistema `tomcat` precisa de acesso de leitura a
   `/etc/letsencrypt/live/.../*.pem` (os próprios diretórios do Let's Encrypt geralmente são
   apenas-root por padrão — ou relaxe as permissões apenas nesses dois arquivos, ou copie-os para
   algum lugar onde o Tomcat possa ler, e recopie a cada renovação). Reinicie o Tomcat, depois use
   `https://<hostname>:8443/...` em todo lugar neste guia em vez de `http://<hostname>:8080/...`.
   Ou remova o conector HTTP simples inteiramente, ou defina seu `redirectPort="8443"` para que uma
   solicitação HTTP perdida seja redirecionada para HTTPS em vez de servida em texto claro. A
   renovação do Certbot não reinicia o Tomcat para você — adicione um
   `--deploy-hook "systemctl restart tomcat9"` (ou um script em `renewal-hooks/deploy/`) para que um
   certificado renovado de fato entre em vigor.

   A seção **Tomcat** em `/admin/settings` constrói/edita exatamente este bloco de conector para
   você — um menu suspenso "HTTPS" mais os dois caminhos PEM — usando o mesmo mecanismo de
   arquivo-é-autoritativo, script-wrapper-SSH descrito em "Usando uma porta diferente" acima. Ela
   nunca remove o conector HTTP simples nem define `redirectPort` para você, e sempre substitui
   completamente os caminhos do elemento `<Certificate>` existente ao salvar, em vez de mesclar —
   se você personalizou o conector além do mostrado aqui (um tipo de certificado que não seja
   `RSA`, múltiplas entradas `SSLHostConfig`, etc.), edite `server.xml` manualmente em vez disso.

Seja qual for a opção escolhida, toda URL `http://` referenciada em outro lugar neste guia —
incluindo dentro de `application.yml`/variáveis de ambiente, não apenas o que um navegador vê —
precisa se tornar `https://` para corresponder; uma incompatibilidade entre o que o nspawnmgr está
configurado e o que de fato é servido é uma causa comum de loops de redirecionamento ou falhas de
cookie-não-enviado.

**Se você estiver usando o modo de aprovação de administrador**
([§3](#3-a-conta-ssh-com-capacidade-de-sudo)), habilitar HTTPS aqui é fortemente recomendado mesmo
que nada mais tenha motivado você a isso: a página de aprovação envia a senha de sudo de um
administrador como um campo de formulário simples, e essa é uma exposição significativamente maior
sobre HTTP em texto claro do que qualquer outra coisa que o nspawnmgr sirva. A instalação
documentada padrão permanece em HTTP — esta é uma recomendação para esse modo específico, não uma
mudança no padrão.

## 7. Guacamole

**Nenhum dos três componentes do Guacamole é um pacote apt em release atual algum do
Debian/Ubuntu/Mint**: `guacd` e `guacamole-tomcat` retornam zero resultados em bookworm, trixie,
jammy, e noble, e até o Debian unstable só constrói `guacd` para `ia64`/`riscv64`, não `amd64`. Cada
um é tratado de forma diferente, e nenhum deles sozinho lhe dá uma configuração funcional:

| Componente | Empacotado? | O que faz |
|---|---|---|
| `guacd` | **Não.** O `.deb` empacota uma construção autocontida em vez disso (OpenSSL 3.x próprio, um FFmpeg mínimo, FreeRDP2, e libssh2 — veja `/usr/share/doc/nspawnmgr/guacd-bundle-README.md` para exatamente por quê e como) e o executa como sua própria unidade systemd `guacd.service` — nenhum pacote de sistema, nenhuma etapa manual, em qualquer opção de instalação. | o próprio daemon proxy nativo |
| `guacamole-tomcat` | **Não.** Também não empacotado (é a *cola de empacotamento* que normalmente implantaria `guacamole.war` para você) — mas o próprio `guacamole.war` é: o `.deb` o implanta diretamente no Tomcat empacotado, o mesmo que `nspawnmgr.war`/`auth.war` (veja abaixo). | implanta o `guacamole.war` no Tomcat automaticamente |
| `guacamole-auth-jdbc` | **Não.** Não é um pacote apt, mas empacotado da mesma forma que o `guacd` — um tarball baixado uma vez, verificado por checksum, e comitado em `packaging/nspawnmgr-deb/vendor/` (veja `vendor/README.md`), não buscado novamente no momento da instalação. O `postinst` do `.deb` o extrai automaticamente, sem necessidade de rede; instalações manuais executam o mesmo script manualmente (veja abaixo). **Obrigatório, não opcional** — veja abaixo. | a extensão JDBC que dá ao Guacamole um backend de armazenamento de conexão MySQL/PostgreSQL, mais seus scripts de esquema SQL |

`guacamole-auth-jdbc` não é uma opção entre vários backends que você poderia escolher em vez disso —
o nspawnmgr gerencia toda conexão e usuário do Guacamole através da API REST do Guacamole (veja
"GUACAMOLE_HOME e o backend de autenticação" abaixo), e essa API só existe quando o Guacamole está
rodando uma extensão de autenticação apoiada em banco de dados. O próprio padrão do Guacamole
(`user-mapping.xml`, um arquivo XML estático sem API) não a expõe. Pular esta etapa não lhe dá um
nspawnmgr funcional com funcionalidade reduzida — lhe dá um nspawnmgr que não consegue criar ou
gerenciar conexão de contêiner alguma, já que toda ação de "dar a este usuário acesso a este
contêiner" acaba chamando esta API. Mesmo com a automação do `.deb`, extrair o tarball é apenas
metade do que a etapa 1 do §7 abaixo descreve — o JAR/driver ainda precisam ser copiados manualmente
para `GUACAMOLE_HOME`, e nem o `guacd` nem o `guacamole.war` sendo implantados implica que nada disso
esteja feito; confirme separadamente.

### guacd

Se você instalou via o `.deb` (§5 Opção A), isso já está feito —
`nspawnmgr-bootstrap-app-machine.sh` extraiu o bundle autocontido para `/opt/guacd-bundle` e iniciou
`guacd.service` **dentro da máquina autoalojada `nspawnmgr`**, não no host (`sudo machinectl shell
nspawnmgr systemctl status guacd` para confirmar) — e pule para "guacamole.war" abaixo.

Caso contrário (Opção B, implantação com Tomcat no host —
[§6](#6-tomcat-9-nspawnmgr--guacamole--auth)), você precisa de um binário `guacd` real de algum
lugar, já que o apt não fornecerá um em release atual alguma. O caminho mais direto é reutilizar a
mesma construção autocontida que o `.deb` envia: `packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz`
em um checkout do repositório (ou construa sua própria cópia seguindo a receita de
`packaging/nspawnmgr-deb/vendor/README.md` — ela documenta cada etapa, incluindo duas armadilhas
reais que custaram tempo real para descobrir: o CMake armazenando silenciosamente em cache um
caminho OpenSSL obsoleto entre reconfigurações, e `-Wl,-rpath` não bastar por si só sem um `-L`
correspondente). Extraia-o e instale a unidade systemd da mesma forma que o `postinst` faz:

```bash
sudo tar -xzf packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz -C /opt
sudo adduser --system --home /nonexistent --no-create-home --group guacd
sudo cp packaging/nspawnmgr-deb/guacd.service /etc/systemd/system/guacd.service
sudo systemctl daemon-reload
sudo systemctl enable --now guacd
```

### guacamole.war

Se você instalou via o `.deb` (§5 Opção A), isso também já está feito —
`nspawnmgr-bootstrap-app-machine.sh` implantou `packaging/nspawnmgr-deb/vendor/guacamole-1.5.5.war`
(a mesma release oficial do Apache, baixada uma vez e verificada por checksum, não buscada
novamente no momento da instalação) via um descritor de contexto apontando para
`/usr/share/nspawnmgr/guacamole.war` **dentro da máquina autoalojada `nspawnmgr`**, ao lado de
`nspawnmgr.war`/`auth.war`. Confirme com
`curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<forwarded port>/guacamole/` (espere
`200`, ou um redirecionamento para o próprio fluxo de login do Guacamole) e pule para
"GUACAMOLE_HOME e o backend de autenticação" abaixo.

Caso contrário (Opção B, implantação com Tomcat no host), baixe e implante o mesmo arquivo você
mesmo:

```bash
GUACAMOLE_VERSION=1.5.5
curl -fsSL -o guacamole.war \
  "https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/guacamole-${GUACAMOLE_VERSION}.war"
sudo cp guacamole.war /opt/tomcat9/webapps/guacamole.war
```

### GUACAMOLE_HOME e o backend de autenticação

O Guacamole precisa de seu próprio `GUACAMOLE_HOME` (comumente `/etc/guacamole`) contendo
`guacamole.properties` mais o JAR de extensão/driver JDBC do `guacamole-auth-jdbc` para seu
**backend de armazenamento de conexão** — isso é uma preocupação separada do próprio banco de dados
do nspawnmgr. **O assistente de banco de dados de primeira inicialização do §4 agora faz as etapas
1–2 abaixo automaticamente** (copiando o JAR de extensão correto para dentro, escrevendo as
propriedades `<vendor>-*`, executando o esquema) como parte de configurar o banco de dados
`guacamole` — o passo a passo abaixo é para fazer isso manualmente em vez disso (sem acesso ao
assistente, a conexão automática falhou e deixou um aviso, ou você está mudando o banco de dados
depois do fato). Se você instalou via o `.deb`, este diretório e um `guacamole.properties` mínimo
(apenas `guacd-hostname`/`guacd-port`, apontado para a própria instância do `guacd` que a mesma
instalação já iniciou) já existem, pertencentes a `tomcat:tomcat` — criados uma vez, apenas na
primeira instalação, então uma edição posterior (manual ou via o editor de Guacamole de
`/admin/settings`) sempre sobrevive a uma atualização. Caso contrário (Opção B), crie-o você mesmo:
`sudo mkdir -p /etc/guacamole && sudo chown tomcat:tomcat /etc/guacamole`. Como coberto acima, a
própria extensão de autenticação JDBC é obrigatória, não uma escolha entre alternativas: o nspawnmgr
gerencia conexões/usuários através da API REST do Guacamole usando uma conta de administrador
(`nspawnmgr.guacamole.admin-username`/`admin-password`), e apenas o `guacamole-auth-jdbc` expõe essa
API. Então:

1. Obtenha o tarball do `guacamole-auth-jdbc` extraído — diferente de `guacd`/`guacamole-tomcat`
   acima, não há pacote apt algum para isso em release alguma, mas como o `guacd`, ele é empacotado
   diretamente em vez de baixado no momento da instalação:
   `packaging/nspawnmgr-deb/vendor/guacamole-auth-jdbc-1.5.5.tar.gz` em um checkout do repositório é
   o mesmo tarball que o `.deb` envia, já baixado uma vez e verificado por checksum contra o próprio
   `.sha256` do Apache. `install-guacamole-auth-jdbc.sh` o extrai (sem necessidade de rede) em um
   **local de instalação opinativo**, fixo e independente de versão, `/etc/guacamole/guacamole-auth-jdbc/`
   (subpastas `mysql/schema/` e `postgresql/schema/`, independentemente de qual banco de dados você
   acabar usando — o tarball envia ambos). Este não é um caminho que o próprio Guacamole exige,
   apenas a própria convenção do nspawnmgr:
   - **Instalações via `.deb`**: isso já rodou automaticamente, como parte do `postinst` — se falhou
     (ex.: o tarball está de alguma forma faltando em `/usr/share/nspawnmgr/`), execute novamente
     `sudo /usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh` manualmente.
   - **Instalações manuais**, ou para refazer isso (ex.: para aumentar a versão do Guacamole —
     revendorize o tarball primeiro): execute
     `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh` a partir de um checkout do
     repositório (flags `--source-tarball`/`--target-dir`/`--force` disponíveis — veja o próprio
     comentário de cabeçalho do script).

   De qualquer forma, a partir de `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/`, copie o
   JAR de extensão para seu banco de dados escolhido (`nspawnmgr.guacamole.data-source`, ex.:
   `mysql`) para `GUACAMOLE_HOME/extensions/` — ainda uma etapa manual, já que depende de uma
   escolha (qual banco de dados) que nada pode fazer por você.

   O próprio driver JDBC (o `java.sql.Driver` real, separado do JAR de extensão acima — o
   `guacamole-auth-jdbc` nunca o empacota) é uma história diferente: o nspawnmgr.war já empacota
   tanto o driver MySQL quanto o PostgreSQL para seu próprio uso de banco de dados, não relacionado
   (`pom.xml` raiz), então em vez de um segundo download separado, `install-guacamole-jdbc-drivers.sh`
   simplesmente copia ambos os jars de driver já construídos do próprio nspawnmgr para
   `GUACAMOLE_HOME/lib/` — nenhum acesso à rede necessário de forma alguma, e nenhum mal em ambos
   estarem ali mesmo que apenas um seja de fato usado. Como o tarball de esquema acima, isso já rodou
   automaticamente como parte do `postinst` do `.deb` (melhor esforço — execute novamente
   `sudo /usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh` se falhou por algum motivo); para uma
   instalação manual, execute
   `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh --source-dir target/guacamole-jdbc-drivers`
   a partir de um checkout do repositório após `mvn -DskipTests package`.
2. Execute o script de esquema dessa extensão contra um banco de dados que o Guacamole possui (este
   **não** é o mesmo banco de dados que o próprio do nspawnmgr — o Guacamole precisa de seu próprio
   esquema de usuários/conexões). A seção Guacamole em `/admin/settings` tem um botão **"Testar
   conexão com o banco de dados"** que faz isso por você: ele se conecta com o que quer que esteja
   atualmente inserido nos campos de Banco de dados, verifica se o esquema parece estar configurado
   (procurando pela tabela `guacamole_connection`), e se não, oferece executar todo arquivo `.sql`
   em um diretório que você apontar. O campo "Diretório de scripts de esquema" já tem como padrão
   `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/schema` (correspondendo ao tipo de banco
   de dados selecionado acima dele), então isso geralmente é um clique de "Testar" sem edição se a
   etapa 1 usou o local opinativo.
3. Crie a conta de administrador que o nspawnmgr usará (`guacadmin`/`guacadmin` é o padrão bem
   conhecido que a extensão JDBC envia na primeira execução — mude a senha imediatamente em uma
   implantação real, e atualize `nspawnmgr.guacamole.admin-password` para corresponder).
4. Defina `guacd-hostname`/`guacd-port` em `guacamole.properties` (padrão `localhost:4822`, tudo
   bem se o guacd rodar no mesmo host).

Reinicie o Tomcat depois de colocar arquivos em `GUACAMOLE_HOME` — o Guacamole não recarrega
extensões a quente.

Aponte o nspawnmgr para ele (`nspawnmgr.guacamole.base-url`) assim que estiver de pé, ex.:
`http://your-hostname:8080/guacamole`. Defina também `nspawnmgr.guacamole.home` (`GUACAMOLE_HOME`,
padrão `/etc/guacamole`) se você usou um caminho não padrão — é disso que o editor de Guacamole de
`/admin/settings` lê/escreve `guacamole.properties` (veja [§9](#9-configurando-o-nspawnmgr)).
Nenhuma configuração extra de permissão necessária: o nspawnmgr e o Guacamole ambos rodam como o
mesmo usuário `tomcat` na mesma instância do Tomcat, e `GUACAMOLE_HOME` já pertence a `tomcat` para
o próprio uso do Guacamole.

## 8. `auth` (backend de login)

`auth.war` é o que de fato verifica um nome de usuário/senha contra suas contas de SO (PAM) ou uma
máquina Windows via SMB, e emite o cookie de sessão compartilhado em que o nspawnmgr confia. Tem
como alvo `javax.servlet` (Servlet 4.0), o mesmo que o nspawnmgr e o webapp do Guacamole, então é
implantado na **mesma instância do Tomcat 9** do §6 — nenhum contêiner de servlet separado
necessário. (Apenas para iteração local rápida, também pode ser executado de forma independente via
`mvn -f auth/pom.xml jetty:run`, que o inicia no Jetty na porta 9092 sem um ciclo de
reconstrução/reimplantação de WAR — não algo que você usaria para uma implantação real.)

Defina isso via context-params em `auth/src/main/webapp/WEB-INF/web.xml` (reconstrua o WAR após
editar) ou as propriedades de sistema correspondentes (`-D...`), documentadas nesse arquivo:

| Configuração | Propriedade de sistema | Finalidade |
|---|---|---|
| `auth.backend` | `AUTH_BACKEND` | `pam` (padrão, contas Linux locais no próprio host do auth) ou `smb` (máquina Windows remota) |
| `smb.server` | `SMB_SERVER` | Obrigatório se `auth.backend=smb` — o host Windows contra o qual autenticar |
| `smb.domain` | `SMB_DOMAIN` | Domínio NTLM opcional |
| `auth.required-group` | `AUTH_REQUIRED_GROUP` | Opcional, apenas `pam` — um grupo Unix; o login é recusado para usuários autenticados que não sejam membros |
| `smb.required-share` | `SMB_REQUIRED_SHARE` | Opcional, apenas `smb` — um compartilhamento SMB em `smb.server`; o login é recusado a menos que o usuário tenha acesso a ele (veja abaixo por que isso é uma verificação de compartilhamento, não de grupo) |
| `cookie.name` | — | Deve corresponder ao `nspawnmgr.auth.cookie-name` do nspawnmgr (padrão `nspawnmgr_session`) |

**Por que `smb` controla acesso via compartilhamento, não filiação a grupo:** o Windows restringe
consultas *remotas* de SAM/grupo a `BUILTIN\Administrators` por padrão (`RestrictRemoteSAM`) — isso
excluiria usuários comuns de sempre passar em uma verificação de grupo, por design,
independentemente de ajustes de registro. O acesso a compartilhamento é uma operação SMB normal,
controlada por ACL, sem tal restrição, então conceda/negue acesso definindo permissões normais de
compartilhamento e NTFS em `smb.required-share` para os usuários que devem/não devem ter permissão
para fazer login.

**`pam` precisa que a conta do Tomcat tenha acesso de leitura a `/etc/shadow`.** Verificar uma senha
via PAM em última análise significa ler o hash do usuário alvo em `/etc/shadow` (modo `640`,
`root:shadow`) — normalmente tratado de forma transparente através do próprio helper `unix_chkpwd`
com setgid-`shadow` do `pam_unix`, independentemente do próprio grupo do processo chamador, mas esse
fallback não é confiável em todo host (uma instalação real bateu exatamente nisso: a promoção
setgid do `unix_chkpwd` silenciosamente não fez efeito para *nenhum* chamador não-root de forma
alguma, então todo login PAM falhava com um simples "Login failed" sem erro acionável no próprio log
do `auth.war`). O `postinst` do `.deb` adiciona `tomcat` diretamente ao grupo `shadow`
(`usermod -aG shadow tomcat`) para contornar isso — o `pam_unix` então consegue ler o próprio
`/etc/shadow`, sem necessidade do fallback do `unix_chkpwd` de qualquer forma. Uma instalação manual
(não-`.deb`) precisa do mesmo: `sudo usermod -aG shadow tomcat`, depois reinicie o Tomcat (a
filiação a grupo só se aplica a processos iniciados *depois* da mudança, não a um já em execução).
Se logins PAM falharem depois disso, verifique `/var/log/auth.log` para a linha real
`pam_unix(login:auth)` — é a forma mais direta de ver o que o próprio PAM rejeitou, já que a página
"Login failed" própria do `auth.war` é deliberadamente genérica (sem dicas de enumeração de
credenciais).

Implante-o em seu próprio caminho de contexto `/auth` na mesma instância do Tomcat 9 que o
nspawnmgr/Guacamole (que tomam `/nspawnmgr` e `/guacamole`) para que ele sirva `/auth/login`,
`/auth/userinfo`, `/auth/logout` (correspondendo ao `nspawnmgr.auth.user-id-url` abaixo):

```bash
sudo cp auth/target/auth.war /opt/tomcat9/webapps/auth.war
```

`tools/scripts/setup-auth-tomcat.sh` é uma referência para exatamente isso, adaptada para testes
locais. As próprias páginas de login/logout do `auth` constroem seus links internos (ex.: "Tentar
novamente") a partir de `request.getContextPath()`, não um caminho fixo, então elas resolvem
corretamente independentemente de estar implantado em `/auth` aqui ou na raiz do servidor (ex.: via
`jetty:run` para iteração local).

### Nome de host e o cookie de sessão compartilhado

O nspawnmgr, o `auth`, e o Guacamole **precisam todos ser alcançáveis através do mesmo nome de
host** — o cookie de sessão que o `auth` define só é útil para o nspawnmgr se ambos estiverem no
mesmo escopo de cookie da mesma origem. Como os três agora compartilham uma instância do Tomcat,
isso é em grande parte automático (mesmo host, mesma porta), mas ainda assim escolha um nome de host
real (não `localhost`, a menos que tudo realmente esteja em uma única máquina que você só vai
acessar como `localhost`), aponte-o para o IP do host no DNS ou `/etc/hosts`, e defina-o uma vez em
**`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME` — editável ao vivo em
`/admin/settings`, "Nome de host externo" sob Host; semeado automaticamente com o nome de host real
desta máquina por `setup-sudo-account.sh` em instalações via `.deb`, veja §5). Esta *não* é a mesma
configuração que `nspawnmgr.host.public-address` logo abaixo dela nessa página — veja a própria
descrição desse campo, ou o [§9](#9-configurando-o-nspawnmgr), para a diferença.

Todo outro lugar onde esse nome de host precisa aparecer é um campo de URL simples, não derivado
automaticamente — `nspawnmgr.auth.user-id-url` (`http://<hostname>:8080/auth/userinfo`),
`nspawnmgr.guacamole.base-url`, e a página de login que administradores/usuários são instruídos a
visitar (`http://<hostname>:8080/auth/login?returnTo=...`) — mas `/admin/settings` preenche essa
lacuna: cada um desses campos de URL tem um botão **"Atualizar hostname/porta/protocolo"** que o
reescreve a partir do Nome de host externo acima mais o próprio estado atual de porta/HTTPS da seção
Tomcat (veja §6), então uma mudança de hostname ou porta só precisa ser digitada em um lugar antes
de clicar pelo resto.

Se você terminar HTTPS na frente disso, o CN/SAN do certificado precisa corresponder a esse nome de
host — uma incompatibilidade aqui é a causa mais comum de "o login funciona mas o nspawnmgr ainda
mostra a página de login necessário."

**Sempre navegue até o nspawnmgr através do mesmo nome de host que
`HOST_EXTERNAL_HOSTNAME`/`AUTH_LOGIN_URL` — não `localhost`, um endereço IP, ou qualquer outro
alias, mesmo que resolva para a mesma máquina.** O cookie que o `auth.war` emite não tem atributo
`Domain` algum, então tem escopo exato para o host:porta que serviu a página de login — o que quer
que `AUTH_LOGIN_URL` aponte, não o nome de host que você originalmente digitou. O
redirecionamento-para-login do nspawnmgr sempre envia o `returnTo` de volta para esse mesmo
host:porta também (independentemente de qual nome de host você começou), então uma incompatibilidade
aqui não entra em loop para sempre, mas você acabará no nome de host canônico em vez daquele que
digitou — mais simples apenas sempre usar o certo desde o início.

### O redirecionamento nspawnmgr → auth

Quando o nspawnmgr não consegue validar um cookie de sessão, ele redireciona o navegador para
`nspawnmgr.auth.login-url` (variável de ambiente `AUTH_LOGIN_URL`) com um parâmetro de consulta
`returnTo` apontando de volta para a página que o usuário estava tentando alcançar; o `auth.war`
redireciona de volta para lá após um login bem-sucedido. Se `login-url` for deixado em branco, o
nspawnmgr em vez disso mostra sua própria página estática "login necessário" sem redirecionamento —
defina `AUTH_LOGIN_URL` para a URL `/auth/login` do `auth` (ex.: `http://<hostname>:8080/auth/login`)
para o fluxo automático completo.

## 9. Configurando o nspawnmgr

Todas as configurações vivem sob `nspawnmgr.*` em `src/main/resources/application.yml`, cada uma
substituível por uma variável de ambiente — veja `site/env/.env.example` para a lista completa como
variáveis de ambiente, e `dev_env/application-dev_env.example.yml` para as mesmas configurações como
YAML. Os grupos importantes:

- **`nspawnmgr.ssh.*`** — a conta com capacidade de sudo do
  [§3](#3-a-conta-ssh-com-capacidade-de-sudo)
  (`SSH_HOST`/`SSH_PORT`/`SSH_USERNAME`/`SSH_PASSWORD`, host sempre `127.0.0.1`), mais
  `SSH_PRIVATE_KEY_PATH`, `SSH_CONNECT_TIMEOUT_MS`, `SSH_STRICT_HOST_KEY_CHECKING`. Deixar
  `SSH_PASSWORD` em branco troca a criação de contêiner para o modo de aprovação de administrador e
  exige que `SSH_PRIVATE_KEY_PATH` seja definido em vez disso (a autenticação de transporte SSH
  precisa de *algo* para se autenticar de qualquer forma).
- **`nspawnmgr.auth.user-is-admin-json`** — JsonPath opcional para papéis de administrador
  gerenciados externamente ([§3](#3-a-conta-ssh-com-capacidade-de-sudo)); deixe em branco para o
  modo padrão gerenciado pelo aplicativo (o primeiro usuário a fazer login se torna administrador,
  gerenciável depois em `/admin/users`).
- **`nspawnmgr.guacamole.*`** — `base-url`, `admin-username`/`admin-password`, `data-source`,
  `home` (`GUACAMOLE_HOME`, padrão `/etc/guacamole`), do [§7](#7-guacamole).
- **`nspawnmgr.auth.*`** — `user-id-url` (valida um cookie existente contra o `auth`),
  `cookie-name`, `login-url` (o alvo de redirecionamento do §8), ajuste de cache/timeout,
  `settings-file` (onde o arquivo de configurações de autenticação compartilhado abaixo é escrito —
  precisa corresponder ao próprio `auth.settings-file`/`AUTH_SETTINGS_FILE` do auth.war, padrão
  `/etc/nspawnmgr/auth-live/auth-settings.properties`).
- **`nspawnmgr.nspawn.*`** — `templates-dir`, `machines-dir`, `settings-dir`,
  `privileged-scripts-dir` do [§2](#2-pré-requisitos-do-host).
- **`nspawnmgr.dns.upstream-servers`** — literais de IP separados por vírgula para os quais o
  dnsmasq encaminha buscas não-`.internal`, padrão `1.1.1.1,9.9.9.9` — veja ["Resolvendo contêineres
  por nome"](#resolvendo-contêineres-por-nome). `hosts-file`/`upstream-servers-file` (quais arquivos
  `ContainerDnsSyncService` escreve) são caminhos em tempo de implantação, não editáveis ao vivo.
- **`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME`) — o nome de host compartilhado
  do [§8](#nome-de-host-e-o-cookie-de-sessão-compartilhado); o que usuários fora deste host usam, e
  o que os botões "Atualizar" de URL de `/admin/settings` puxam para toda URL de Guacamole/Auth.
- **`nspawnmgr.host.public-address`** (`HOST_PUBLIC_ADDRESS`) — uma configuração diferente,
  facilmente confundida com a acima, não mais usada pelo caminho SSH/RDP (o `guacd` e a própria
  verificação de prontidão do nspawnmgr agora discam diretamente para o endereço veth interno de um
  contêiner GERENCIADO — veja [Rede de contêineres](#rede-de-contêineres)). Seu único consumidor
  restante é a verificação "HOST_PUBLIC_ADDRESS não é loopback" na página de Diagnóstico de Rede; se
  essa verificação ainda vale a pena manter é algo que merece uma revisão futura, mas ainda não foi
  reexaminado. `setup-sudo-account.sh` ainda autodetecta e semeia o endereço real deste host aqui na
  instalação.
- **`nspawnmgr.crypto.secret-key`** (`APP_SECRET_KEY`) — gere com `openssl rand -base64 32`; usado
  para criptografar segredos que o nspawnmgr armazena (ex.: credenciais do Guacamole que ele
  gerencia por contêiner). Perder/rotacionar isso invalida qualquer coisa já criptografada com a
  chave antiga.
- **`nspawnmgr.provisioning.*`** — `admin-account-name` (a conta de fallback que o nspawnmgr cria
  dentro de um contêiner novo quando o próprio nome de usuário de seu proprietário não pode ser
  usado — veja "Usuários de contêiner" abaixo), `rdp-password-length`.
- **`CONTAINER_CLI_EXECUTOR=real`** — precisa ser `real` para uma implantação real; `fake` é apenas
  para desenvolvimento/CI, e nunca toca em SSH/sudo/senhas de forma alguma independentemente do modo
  de criação de contêiner acima. Seleciona quais beans Spring são conectados na inicialização do
  contexto, então não pode ser mudado em tempo de execução de forma alguma — não exposto em
  `/admin/settings`, deliberadamente: esta é uma escolha em tempo de implantação, e dado o que
  `fake` faz (toda operação de contêiner se torna um no-op silencioso), não vale o risco de expor
  como uma alternância em tempo de execução.

Defina `SPRING_PROFILES_ACTIVE=prod` — isso ativa os executores reais apoiados em SSH em vez das
simulações em memória usadas para desenvolvimento local.

### Configurações editáveis em tempo real (`/admin/settings`)

Um subconjunto dos grupos acima também pode ser mudado em tempo de execução em `/admin/settings`
(apenas administrador): `guacamole.base-url`/`data-source`,
`host.external-hostname`/`public-address`, todo campo `auth.*` incluindo `http-timeout-ms`,
`provisioning.admin-account-name`/`rdp-password-length`, `nspawnmgr.ssh.*`, `nspawnmgr.nspawn.*`, e
`nspawnmgr.dns.upstream-servers`. Esses têm efeito imediato para toda solicitação/alocação
subsequente — `SettingsService` mantém um snapshot em memória atualizado no momento em que uma
mudança é salva, não uma leitura de banco de dados por solicitação. Uma exceção, destacada na
própria página:

- **`nspawnmgr.nspawn.privileged-scripts-dir`** tem efeito imediato como tudo o mais em seu grupo,
  mas mudá-la *sem também atualizar* os caminhos fixos de `/etc/sudoers.d/nspawnmgr_exec` para
  corresponder quebra **toda** operação privilegiada (início/parada de contêiner, sincronização de
  acesso de saída, Reiniciar Tomcat abaixo) — o sudo falha de forma segura, simplesmente recusando o
  caminho novo, em vez de seguir esta configuração. Não há validação ao vivo para esta (é um caminho
  local, possivelmente nem criado ainda no momento de salvar) — apenas o aviso mostrado na página.
- **`nspawnmgr.dns.upstream-servers`** tem efeito no próprio snapshot de `SettingsService`
  imediatamente como tudo o mais, mas alcançar o próprio dnsmasq em execução está um passo removido
  disso: `ContainerDnsSyncService` só capta o valor novo, reescreve
  `/etc/dnsmasq.d/nspawnmgr-upstream.conf`, e reinicia o dnsmasq em sua própria verificação de ~15s
  — veja ["Resolvendo contêineres por nome"](#resolvendo-contêineres-por-nome) para saber por que
  isso é um `systemctl restart` completo, não apenas uma recarga.

**Tudo o mais permanece estático/variável-de-ambiente/apenas-com-reinício**, deliberadamente:
`nspawnmgr.crypto.secret-key`/`nspawnmgr.guacamole.admin-username`/`admin-password` (segredos, mais
o fato de que rotacionar a chave de criptografia ao vivo invalidaria qualquer coisa já criptografada
com a antiga), e `CONTAINER_CLI_EXECUTOR` (veja acima). Hosts não são uma configuração estática de
forma alguma — são totalmente gerenciados pelo administrador via a própria página de detalhes de
cada host e `/admin/hosts/new` (veja "Hosts: máquinas externas gerenciadas por administrador"
acima).

Toda mudança é validada antes de ser aceita:
- **URL base do Guacamole, URL de ID de usuário do auth, URL de login do auth**: uma sondagem de
  alcançabilidade HTTP ao vivo (qualquer resposta, mesmo um 404, conta como alcançável — isso só
  prova que a URL resolve para algo escutando, não que a própria autenticação seja bem-sucedida).
- **Os cinco campos JsonPath**: precisam compilar como expressões JsonPath válidas.
- **Endereço público do host**: apenas formato (sintaxe de hostname/IP) — deliberadamente *não*
  sondado, já que um endereço público frequentemente só é alcançável de fora deste host;
  autossondá-lo não provaria nada.
- Nome do cookie, TTL do cache, nome da conta de administrador, e comprimento da senha de RDP
  recebem verificações básicas de formato/intervalo.
- **`dns.upstream-servers`**: precisa ser uma lista separada por vírgula de literais de IP (IPv4 ou
  IPv6) — um nome de host é rejeitado, já que a própria diretiva `server=` do dnsmasq precisa de um
  que já seja resolvível sem servidor DNS algum (é o que o próprio dnsmasq usa para resolver tudo o
  mais).
- **`ssh.*`**: se qualquer campo SSH estiver presente na mudança enviada, uma conexão SSH real é
  aberta com as configurações *resultantes* (apenas login de transporte — sem execução de comando,
  então isso não depende da concessão de sudoers NOPASSWD estar correta) antes que a mudança seja
  aceita. A página de configurações sempre reenvia todo campo junto (como toda outra seção aqui),
  então na prática isso roda a cada salvamento da interface — da mesma forma que as sondagens de
  alcançabilidade de URL de Guacamole/auth já existentes fazem. Chamar a API diretamente com um
  payload parcial que omite toda chave `ssh.*` a pula.

#### Seção Auth (condicional à detecção do auth.war)

Se o auth.war parecer alcançável (uma sondagem ao vivo de `auth.login-url`), `/admin/settings`
também mostra uma seção para a **própria** configuração de backend do auth.war: `auth.backend`
(`pam`/`smb`), servidor/domínio SMB, e os controles de grupo-obrigatório/compartilhamento-obrigatório
do [§8](#8-auth-backend-de-login) — hoje esses só vivem nos context-params/propriedades de sistema
do `web.xml` do auth.war, fixos no momento da implantação.

Salvar esta seção (junto com o nome do cookie acima, com o qual o auth.war também precisa
concordar — é ele que de fato define o cookie) os escreve no arquivo de propriedades compartilhado
em `nspawnmgr.auth.settings-file`. `AuthConfig` verifica esse arquivo **primeiro**, a cada
solicitação, antes de suas próprias context-params/propriedades de sistema — então um salvamento
aqui tem efeito na próxima solicitação do auth.war, sem reinício de nenhum dos dois webapps. Um
valor em branco/não definido aqui apenas significa "sem substituição"; o auth.war recorre ao próprio
padrão de `web.xml`/propriedade de sistema exatamente como antes disso existir. A escrita do arquivo
é de melhor esforço: se falhar (ex.: uma instalação manual pulou a configuração de
`/etc/nspawnmgr/auth-live/` no [§5](#5-instalando-o-nspawnmgr)), o salvamento no banco de dados ainda
é bem-sucedido e um aviso é registrado — isso não bloqueia o resto da atualização de configurações.

#### Seção Guacamole (condicional)

Se o Guacamole parecer alcançável (uma sondagem ao vivo de `guacamole.base-url`),
`/admin/settings` também mostra um editor estruturado para `guacamole.properties` (em
`nspawnmgr.guacamole.home`): campos individuais para `guacd-hostname`/`guacd-port`/`guacd-ssl`, mais
um seletor de tipo de banco de dados (MySQL/MariaDB ou PostgreSQL) que revela todo campo que a
extensão `guacamole-auth-jdbc` correspondente suporta — conexão, SSL/TLS, política de senha, limites
de concorrência por conexão, integração de autenticação externa, e aplicação de janela de acesso. Os
rótulos de campo e o texto de ajuda são obtidos diretamente do [manual do Apache
Guacamole](https://guacamole.apache.org/doc/gug/configuring-guacamole.html) (páginas de extensão de
autenticação [MySQL](https://guacamole.apache.org/doc/gug/mysql-auth.html) /
[PostgreSQL](https://guacamole.apache.org/doc/gug/postgresql-auth.html)), não inventados localmente.

Carregar a página lê o arquivo existente e pré-preenche todo campo, incluindo qualquer senha já
definida (renderizada em um `<input type="password">` mascarado padrão, o mesmo que mudar uma
credencial salva em qualquer outro lugar deste aplicativo — não visível em texto simples na tela,
mas note que esta é uma escolha de design deliberada: diferente do resto de `/admin/settings`, que
mantém segredos completamente fora da superfície de edição ao vivo, todo o propósito deste editor é
permitir que um administrador veja e ajuste uma configuração de BD do Guacamole já existente sem
precisar conectar via SSH). Salvar só toca nas chaves documentadas acima: limpa as chaves da
extensão de banco de dados que você *não* selecionou (para que o arquivo não acumule configuração
obsoleta de uma escolha anterior) e preserva qualquer outra chave já no arquivo intocada (ex.: as
próprias configurações de uma extensão adicionada manualmente). Salvar **não** reinicia o Tomcat —
o Guacamole não verá a mudança até que você faça isso (`sudo systemctl restart tomcat9`).

#### Relatório de configurações

"Baixar relatório de configurações" produz um arquivo em texto simples com toda configuração da
página (mais o `DB_URL`/`DB_USERNAME`/`DB_VENDOR` persistido do assistente de banco de dados e os
valores de arquivo atuais do editor estruturado do Guacamole), agrupado da mesma forma que a própria
página. Todo valor com formato de senha — `ssh.password`, `DB_PASSWORD`, qualquer chave
`*-password` do Guacamole — é substituído por um literal `********`: o relatório confirma *que* um
valor está definido, nunca qual é ele.

#### Reiniciar Tomcat

Dispara `sudo systemctl restart --no-block tomcat9` através da mesma conta SSH com capacidade de
sudo e concessão de sudoers NOPASSWD que toda outra operação privilegiada rotineira já usa (veja
[§3](#3-a-conta-ssh-com-capacidade-de-sudo)) — o `.deb` envia o script wrapper necessário
(`/usr/lib/nspawnmgr/privileged/nspawnmgr-restart-tomcat.sh`) e a entrada de sudoers
automaticamente. Uma instalação manual (não-`.deb`) precisa adicionar ambos manualmente: copie o
script de `packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-restart-tomcat.sh` para dentro de
`nspawn.privileged-scripts-dir`, depois adicione seu caminho ao alias `NSPAWNMGR_NOPASSWD` em
`/etc/sudoers.d/nspawnmgr_exec` (valide com `visudo -cf` antes de confiar nele).

O reinício é disparado de forma assíncrona (`--no-block` enfileira o job systemd e retorna quase
instantaneamente) em vez de esperado — esperar não funcionaria de qualquer forma, já que a própria
solicitação pedindo o reinício é servida pela instância do Tomcat prestes a cair. Depois de clicar no
botão e confirmar, a página espera 5 segundos, limpa o cookie de sessão do lado do cliente, e
recarrega — pousando de volta na página de login assim que o aplicativo (já reiniciado, a essa
altura) vir o cookie ausente, da mesma forma que faria para qualquer outra sessão expirada.

## 10. Verificando a implantação

**Em uma instalação `.deb`** (autoalojada — [§1](#1-visão-geral-da-arquitetura)):
`<hostname>:<port>` abaixo significa a porta que a instalação imprimiu durante o `postinst` (8080 a
menos que já esteja ocupada), e comandos de `machinectl list`/verificação de log precisam de
`sudo machinectl shell nspawnmgr <command>` — os logs do Tomcat, `guacd`, e ambos os WARs vivem
dentro dessa máquina, não no host. Em uma instalação manual, Opção B (Tomcat no host), tudo abaixo
roda diretamente no host em vez disso, o mesmo que sempre fez.

1. Confirme que a máquina autoalojada `nspawnmgr` está de pé: `sudo machinectl list` no host deveria
   mostrá-la `running` (e, assim que você tiver passado pelo §4, sua máquina de banco de dados
   também). Dentro dela, `guacd` e o Tomcat (`nspawnmgr.war` + `guacamole.war` + `auth.war`) deveriam
   ambos estar em execução.
2. Visite `http://<hostname>:<port>/auth/login` diretamente e confirme que você consegue fazer login
   com a conta inicial criada durante o assistente do §4 (e, se configurado, que uma conta fora de
   `auth.required-group`/`smb.required-share` é corretamente recusada).
3. Visite `http://<hostname>:<port>/nspawnmgr/` sem cookie algum presente — você deveria ser
   redirecionado para a página de login do `auth` e, após fazer login, de volta para o nspawnmgr. As
   máquinas `nspawnmgr`/banco de dados já deveriam aparecer como contêineres comuns na lista de
   contêineres neste ponto — o assistente as registra diretamente, sem login necessário primeiro.
4. Crie um contêiner novo através da interface do nspawnmgr e confirme que ele de fato inicializa
   (`sudo machinectl list` no host deveria mostrá-lo) e que uma conexão Guacamole aparece para ele.
5. Verifique a própria página "Ver log" do nspawnmgr (assim que ele estiver pelo menos avançado o
   suficiente para servir páginas), ou `sudo machinectl shell nspawnmgr journalctl -u tomcat9` para
   falhas de nível mais baixo, se algo acima falhar — a maioria dos problemas de primeira implantação
   é uma incompatibilidade de hostname/cookie (§8) ou a conta de sudo (§3) não tendo de fato
   acesso sudo/SSH configurado corretamente.

## 11. Operações do dia a dia

- **Logs**: `<tomcat-dir>/logs/catalina.out.<date>.log` para a instância única do Tomcat (nspawnmgr,
  Guacamole, e auth todos registram ali); `journalctl -u guacd` para o próprio daemon proxy do
  Guacamole — em uma instalação `.deb` (autoalojada), ambos vivem *dentro* da máquina `nspawnmgr`
  (`sudo machinectl shell nspawnmgr <command>`), não no host. O `.deb` conecta o próprio
  stdout/stderr do Tomcat através do `rotatelogs` (`apache2-utils`) via `ExecStart` de
  `tomcat9.service`, produzindo um arquivo datado novo diariamente — diferente de um
  `catalina.sh start` simples, o `tomcat9.service` deste pacote executa `catalina.sh run`
  diretamente, o que nunca produz um `catalina.out` sem data por conta própria (isso é só o que você
  veria rodando o Tomcat interativamente, ex.: o dev stack). Todo usuário logado pode ver as últimas
  100 linhas e o log atual completo na própria página "Ver log" do nspawnmgr; administradores também
  podem navegar e excluir dias individuais já rotacionados a partir de lá.
- **Reiniciando**: reinicie o Tomcat após mudar qualquer configuração de `-D`/variável de ambiente —
  nenhuma delas é recarregada a quente, e já que os três webapps compartilham uma instância,
  reiniciá-la reinicia os três juntos. Reinicie apenas o `guacd` depois de mudar
  `guacd-hostname`/`guacd-port` em `guacamole.properties`.
- **Backups**: faça backup do próprio banco de dados do nspawnmgr (metadados de contêiner/usuário),
  do próprio banco de dados do Guacamole (histórico/parâmetros de conexão), e de
  `/var/lib/machines` (sistemas de arquivos raiz dos contêineres) separadamente — são
  armazenamentos independentes sem integridade referencial cruzada aplicada além do que o
  nspawnmgr gerencia em nível de aplicativo.
- **Rotacionando `APP_SECRET_KEY`**: não há ferramenta de recriptografia embutida; trate isso como
  uma operação de emergência, planejada com antecedência, não algo para mudar casualmente em um
  sistema em execução.
- **Solicitações pendentes de contêiner** (apenas modo de aprovação de administrador): aparecem em
  `/requests`. `DENIED` é atualmente um estado terminal — não há recurso de reenvio, o usuário
  solicitante precisa criar um contêiner novo do zero.
