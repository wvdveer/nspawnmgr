# Guide de l'administrateur nspawnmgr

Ce guide explique comment mettre en place un véritable déploiement de production de nspawnmgr
depuis zéro : l'hôte Linux et `systemd-nspawn`, la base de données, Tomcat, Apache Guacamole,
l'application de connexion `auth`, et nspawnmgr lui-même. Il suppose un unique hôte Linux de la
famille Debian/Ubuntu exécutant tout, ce qui est l'arrangement sur lequel le projet lui-même est
construit et testé ; adaptez les chemins/noms de paquets si vous utilisez une distribution
différente.

Pour la boucle de développement locale (simulations, pas de vrais conteneurs, pas de vrai
Guacamole), voir `site/env/README.md` et `dev_env/README.md` à la place — ce guide traite d'un
véritable déploiement.

## 1. Vue d'ensemble de l'architecture

**nspawnmgr s'exécute depuis l'une de ses propres machines systemd-nspawn** — un conteneur Debian
auto-hébergé nommé `nspawnmgr`, créé automatiquement par le `postinst` du `.deb`
(`nspawnmgr-bootstrap-app-machine.sh`) avant même qu'un administrateur ne touche à l'application.
Seul un petit ensemble fixe de choses reste sur l'hôte nu :

| Reste sur l'hôte | Pourquoi |
|---|---|
| `nspawnmgr_exec` (le compte SSH doté des droits sudo, [§3](#3-le-compte-ssh-doté-des-droits-sudo)) | La création/gestion de conteneurs nécessite un accès root réel sur l'hôte nu — c'est le seul compte qui l'a |
| Les modèles et paquets (`/var/lib/nspawnmgr/templates`, le cache de paquets admin) | Stockage partagé, côté hôte, à partir duquel chaque conteneur (y compris celui de nspawnmgr lui-même) est construit |
| `nspawnbr0` (le pont partagé) et dnsmasq | Le réseau auquel chaque conteneur, y compris ceux auto-hébergés, se rattache |

Tout le reste — Tomcat, les quatre WAR (`nspawnmgr.war`, `auth.war`, `guacamole.war`, `ROOT.war`),
et `guacd` — s'exécute **à l'intérieur** de la machine `nspawnmgr`, tous dans une seule instance
Tomcat 9 là-bas, chacun à son propre chemin de contexte (`/nspawnmgr`, `/auth`, `/guacamole`, et
`/` pour `ROOT.war`) exactement comme avant — seul *l'endroit* où s'exécute cette instance Tomcat a
changé, pas la façon dont les quatre WAR sont disposés les uns par rapport aux autres. Voir le
commentaire en haut du `pom.xml` racine pour savoir pourquoi nspawnmgr lui-même est figé sur
Boot 2.7/Tomcat 9 (pour correspondre à la propre webapp de Guacamole, qui ne peut pas s'exécuter
sur Jakarta EE/Tomcat 10+ sans modification) et le commentaire en haut de `auth/pom.xml` pour le
même raisonnement appliqué à `auth`.

Comme la machine `nspawnmgr` n'a aucun accès réseau à l'hôte (seulement un veth ordinaire vers
`nspawnbr0`, comme tout autre conteneur), `postinst` choisit également un port hôte libre (8080,
ou le prochain libre — il indique lequel) et le redirige directement vers le `:8080` propre de
cette machine via une ligne `Port=` dans son fichier `.nspawn`, le même mécanisme que les
[mappages de ports personnalisés](#mappages-de-ports-personnalisés-et-accès-sortant) utilisent
pour les conteneurs ordinaires. Naviguer vers `http://<cet hôte>:<ce port>/` atteint donc toujours
nspawnmgr exactement comme avant — l'auto-hébergement est invisible du côté du navigateur.

Le backend PAM de `auth.war` (le backend par défaut — voir [§8](#8-auth-backend-de-connexion))
s'authentifie contre quel que soit l'hôte où vivent les comptes OS locaux de sa propre JVM. Comme
`auth.war` s'exécute désormais à l'intérieur de la machine `nspawnmgr`, cela signifie que ce sont
ses propres comptes — créés pendant l'[assistant de configuration au premier
démarrage](#assistant-de-configuration-au-premier-démarrage), pas ceux de l'hôte nu — sans qu'aucun
code de backend ni configuration ne soit nécessaire pour que cela soit vrai.

La base de données est également auto-hébergée : l'assistant de configuration au premier démarrage
provisionne sa propre machine de base de données Debian (voir [§4](#4-base-de-données)) plutôt que
de se connecter à un serveur existant. La machine `nspawnmgr` et sa machine de base de données
apparaissent toutes deux comme des conteneurs ordinaires et visibles dans la propre liste de
conteneurs de nspawnmgr dès que l'assistant de premier démarrage se termine — voir la note du
[§4](#4-base-de-données) à ce sujet. Les deux sont également configurées pour
[démarrer automatiquement au démarrage de l'hôte lui-même](#démarrage-automatique-au-démarrage-de-lhôte),
avec `nspawnmgr` configuré pour exiger que sa machine de base de données soit déjà démarrée —
sinon un redémarrage de l'hôte pourrait démarrer la machine `nspawnmgr` avant même que sa machine
de base de données ne soit prête, la laissant tourner sans base de données accessible jusqu'à ce
que quelqu'un le remarque et démarre l'autre machine à la main.

nspawnmgr lui-même n'exécute jamais `machinectl`/`systemd-run` directement — le compte sous lequel
Tomcat s'exécute n'a pas de sudo, où que Tomcat lui-même s'exécute. À la place, nspawnmgr se
connecte en SSH au **compte `nspawnmgr_exec` séparé, doté des droits sudo, sur l'hôte nu** et y
exécute des commandes privilégiées en tant que root — les opérations routinières
(démarrer/arrêter/supprimer un conteneur, synchronisation du pare-feu) sans jamais avoir besoin
d'un mot de passe, et seules les opérations plus risquées, uniquement au moment de la création
(qui exécutent du contenu défini par un modèle en tant que root à l'intérieur d'un nouveau
conteneur, ou provisionnent une toute nouvelle machine) en nécessitant un, provenant soit d'une
configuration stockée soit d'une approbation admin par requête. Sur une installation empaquetée,
cette connexion SSH cible l'adresse fixe propre à `nspawnbr0` (`10.100.0.1`) plutôt que
`127.0.0.1`, puisque nspawnmgr atteint l'hôte *depuis* l'intérieur de sa propre machine plutôt que
de se parler à lui-même — configuré automatiquement par `nspawnmgr-bootstrap-app-machine.sh`, rien
à configurer à la main. Configurer ce compte est l'une des étapes les plus importantes et les plus
faciles à manquer ci-dessous ([§3](#3-le-compte-ssh-doté-des-droits-sudo)).

## 2. Prérequis de l'hôte

Sur l'hôte Linux qui exécutera les conteneurs :

```bash
sudo apt update
sudo apt install -y systemd-container openssh-server
```

`systemd-container` fournit `machinectl`, `systemd-nspawn`, et `systemd-run` — y compris
`machinectl import-tar`, que nspawnmgr utilise pour cloner un modèle de conteneur dans une nouvelle
machine (parle à `systemd-importd`, activé par socket de la même façon que `systemd-machined`
l'est pour `machinectl start`, donc cela devrait simplement fonctionner sans configuration
séparée). Confirmez que les bases fonctionnent :

```bash
machinectl list-images   # should run without error, even with an empty list
```

nspawnmgr s'attend à ce que deux répertoires existent et soient accessibles en écriture par le
compte doté des droits sudo (créés automatiquement par `systemd-nspawn`/`machinectl` la première
fois qu'ils sont utilisés, mais cela vaut la peine de le confirmer) :

- `/var/lib/machines` — où vivent les systèmes de fichiers racine des conteneurs
  (`NSPAWN_MACHINES_DIR`)
- `/etc/systemd/nspawn` — où vivent les fichiers de paramètres `.nspawn` par conteneur
  (`NSPAWN_SETTINGS_DIR`)

Ce sont des **chemins système réels et fixes** — `machinectl`/`systemd-nspawn` ne regardent jamais
ailleurs, quoi que dise la propre configuration de nspawnmgr. N'essayez pas de les mettre en
bac à sable.

### Bases de données (deux, séparées — une pour nspawnmgr et une pour Guacamole)

Prévoyez **deux bases de données indépendantes**, toutes deux sur le même serveur MySQL/MariaDB ou
PostgreSQL : le propre schéma utilisateurs/conteneurs/paramètres/modèles de nspawnmgr, et le propre
schéma utilisateurs/connexions/permissions de Guacamole (géré séparément par l'extension
`guacamole-auth-jdbc` de Guacamole). **MySQL/MariaDB ou PostgreSQL uniquement — aucune option H2.**
Voir [§4](#4-base-de-données) — l'assistant de configuration au premier démarrage crée les deux
bases de données pour vous, avec des noms fixes et arbitraires (`nspawnmgr`/`guacamole`), donc il
n'y a rien à préparer à la main à l'avance.

### Modèles de conteneurs (systèmes de fichiers racine de base)

nspawnmgr provisionne de nouveaux conteneurs en clonant un « modèle » dans `/var/lib/machines` via
`machinectl import-tar`. Les modèles eux-mêmes vivent sous `TEMPLATES_DIR` (par défaut
`/var/lib/nspawnmgr/templates`), un sous-répertoire par backend — `nspawn/`, `podman/`, et `qemu/`
(voir [« Podman : pods »](#podman--pods) et [« QEMU : machines
virtuelles »](#qemu--machines-virtuelles) ci-dessous pour les formats de modèles propres aux deux
autres backends et comment chacun est peuplé — cette section concerne spécifiquement les fichiers
`<nom>.tar.gz` de nspawn : de simples tars gzippés d'un système de fichiers racine, exactement ce
que `machinectl import-tar` lui-même consomme). Vous devez en préparer au moins un vous-même, réel
et amorçable — nspawnmgr ne les télécharge ni ne les construit pour vous, à une exception près :
`/admin/templates` propose trois boutons indépendants **« Configurer X-minimal »** —
**debian-minimal** (APT), **fedora-minimal** (DNF), **arch-minimal** (PACMAN) — chacun affiché
uniquement tant que le modèle de cette saveur spécifique n'existe pas encore (en configurer un ne
masque pas les autres ; configurez-en un, plusieurs, ou les trois). Chacun télécharge un vrai
minirootfs (vérifié par somme de contrôle) depuis images.linuxcontainers.org, installe et active
un serveur SSH dedans, l'empaquette sous `TEMPLATES_DIR/nspawn/<saveur>-minimal.tar.gz`, et
l'enregistre avec son indicateur « SSH préinstallé » activé — un modèle réel et fonctionnel en un
clic. Cet indicateur (également réglable sur tout modèle créé à la main, voir son formulaire
d'édition) indique à la création de conteneur que l'image a déjà SSH installé et activé, sautant
l'étape de téléchargement/installation/activation autrement redondante dont chaque autre modèle a
besoin. Ce n'est pas un outil de gestion de modèles généraliste : il n'y a pas de bouton équivalent
pour un nom personnalisé, et chaque bouton disparaît une fois que le modèle de sa propre saveur
spécifique existe (indépendamment des autres modèles existants). Même exigence sudo que tout le
reste, uniquement au moment de la création (§3) — en mode approbation admin, on vous demandera le
mot de passe sudo en ligne. Voir
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-{debian,fedora,arch}-template.sh`
pour savoir exactement ce que fait chacun — **seul celui de Debian a été confirmé contre un vrai
conteneur** ; voir [« Modèles Fedora et Arch : état de
vérification »](#modèles-fedora-et-arch--état-de-vérification) ci-dessous pour l'état de
vérification des deux autres, et pour l'approche à double voie (native à l'hôte vs. chroot) que les
trois scripts partagent désormais. Les propres
`site/templates/nspawn/{debian-minimal,fedora-minimal,arch-minimal,alpine-minimal}` du dépôt sont
une chose *différente* — de minuscules répertoires d'espace réservé (pas même des tarballs)
utilisés uniquement pour les tests en mode développement local (voir
`site/templates/README.md`) — **ne les utilisez pas comme de vrais modèles**, ils ne sont pas
amorçables.

Délibérément aucune saveur Alpine parmi les trois : le minirootfs officiel d'Alpine n'a pas du tout
de systemd/D-Bus (il utilise OpenRC), et chaque commande dans le conteneur que nspawnmgr exécute
passe par `systemd-run --machine=`, qui exige que le conteneur lui-même exécute systemd — un
conteneur basé sur Alpine échoue avec « Failed to connect to bus » de façon permanente, pas comme
une simple course au démarrage transitoire méritant une nouvelle tentative. Un vrai support Alpine
nécessiterait que systemd soit installé et fonctionne comme PID 1 à l'intérieur du conteneur
d'abord, ce qui n'est pas standard sur Alpine et n'a pas été testé ici.

#### Modèles Fedora et Arch : état de vérification

**debian-minimal est le seul des trois boutons « Configurer X-minimal » confirmé contre un vrai
conteneur** — il a été créé et démarré en direct plusieurs fois au cours de ce projet.
**fedora-minimal** et **arch-minimal** restent spécifiquement non vérifiés : de vrais hôtes
Fedora/Arch existent bel et bien et ont été largement utilisés ailleurs dans ce projet (voir les
sections d'installation des paquets RPM/Arch ci-dessus), mais `nspawnmgr-create-fedora-template.sh`/
`nspawnmgr-create-arch-template.sh` — les scripts que ces deux boutons spécifiques de l'interface
admin appellent — n'ont jamais réellement été exercés contre un vrai conteneur systemd-nspawn. Si
vous essayez l'un ou l'autre, merci de rapporter ce qui casse — quelques zones de risque connues
spécifiques, à peu près dans l'ordre de probabilité qu'elles posent problème :

- **Les trois scripts de fabrication (Debian, Fedora, Arch) détectent la propre distribution de
  l'HÔTE et choisissent l'une de deux voies d'installation en conséquence**, plutôt que de supposer
  une seule distribution. Chaque script vérifie `command -v apt-get`/`dnf`/`pacman` pour son PROPRE
  gestionnaire de paquets cible : si l'hôte en a un correspondant, il exécute cet outil comme un
  **processus côté hôte** normal pointé vers le rootfs extrait (la combinaison `-o Dir=`/
  `-o DPkg::Options::=--root=` d'apt, `dnf --installroot=`, `pacman --root=`). Si l'hôte n'a aucun
  gestionnaire de paquets correspondant du tout (par ex. nspawnmgr déployé sur un hôte Debian
  fabriquant un modèle Fedora ou Arch, ou l'inverse), le script fait plutôt un **`chroot` dans le
  rootfs fraîchement extrait et utilise la propre copie embarquée de l'outil de l'image** —
  `/etc/resolv.conf` copié dedans (chroot ne partage pas la configuration réseau de l'hôte),
  `/dev`/`/proc`/`/sys`/`/run` montés en bind avant que l'installation chrootée ne s'exécute (le
  montage bind de `/run` rend spécifiquement le module NSS de `systemd-resolved` accessible pour la
  résolution DNS à l'intérieur du chroot — sans lui, la résolution de noms peut échouer même avec
  un `/etc/resolv.conf` correct en place), démonté à nouveau immédiatement après, avant que le
  tarball ne soit empaqueté — la même technique que l'étape chroot propre de
  `pacstrap`/`arch-chroot`/`debootstrap` utilise. Seule la branche côté hôte du script Debian
  (Debian-sur-Debian) a réellement été exercée contre un vrai conteneur ; le repli chroot du script
  Debian, et les deux branches des scripts Fedora/Arch, sont construits selon les spécifications
  mais non vérifiés — ces scripts spécifiques de fabrication de modèle de conteneur n'ont jamais
  été exécutés pour de vrai, même si de vrais hôtes Fedora/Arch existent et sont utilisés ailleurs
  dans ce projet.
- **arch-minimal est le plus spéculatif des trois.** Zones de risque connues : (1) le
  `/etc/pacman.d/mirrorlist` de l'image téléchargée est livré avec chaque miroir commenté selon la
  propre convention d'Arch — le script écrit explicitement `geo.mirror.pkgbuild.com` (le
  redirecteur GeoIP officiel d'Arch) ; (2) la vérification de signature des paquets nécessite un
  trousseau de clés peuplé que ce script ne met pas en place (le vrai `pacstrap` le fait, via
  `pacman-key --init`/`--populate`) — plutôt que de tenter cela à l'aveugle sans moyen de le tester,
  le script désactive la vérification de signature (`SigLevel = Never` dans le `pacman.conf` de la
  cible) pour cette installation d'amorçage, un véritable compromis de sécurité qu'il vaut la peine
  de connaître même si c'est raisonnable pour un modèle de démarrage rapide dev/test ; (3) la
  branche chroot désactive aussi `CheckSpace` dans `pacman.conf` — la vérification d'espace disque
  de pacman résout le répertoire de cache vers un point de montage via `/proc/self/mountinfo`, qui
  à l'intérieur d'un chroot reflète toujours les chemins absolus propres de l'hôte plutôt que le
  `/` remappé du chroot, donc la vérification échoue avec un trompeur « espace disque libre
  insuffisant » quel que soit l'espace réellement disponible (une limitation connue de
  pacman-en-chroot) ; (4) `pacman.conf` reçoit aussi `DisableSandbox` — le bac à sable de
  téléchargement basé sur Landlock de pacman (plus un utilisateur `alpm` non privilégié dédié vers
  lequel il bascule) est bloqué par le filtre seccomp par défaut de `systemd-nspawn` une fois qu'un
  conteneur démarre réellement et exécute `pacman` en direct (par opposition au propre `chroot`
  côté hôte de ce script, qui n'a aucune restriction seccomp) — chaque invocation de `pacman` à
  l'intérieur d'un vrai conteneur en cours d'exécution a besoin de cela pour fonctionner du tout,
  pas seulement pour l'étape de fabrication propre à ce script.
- **RDP est totalement indisponible pour `arch-minimal`.** Confirmé en direct : `xrdp`/`xorgxrdp`
  ont été retirés des dépôts officiels d'Arch (`pacman -Ss xrdp` ne trouve ni l'un ni l'autre, sur
  un miroir fraîchement synchronisé et entièrement peuplé — pas un problème de cache périmé ou de
  mauvais miroir) et cette application n'a pas de support AUR pour se replier. `arch-minimal` règle
  son propre état RDP sur « non capable » par défaut (voir le sélecteur « RDP » de la page admin
  Templates), ce qui désactive réellement l'option « Activer RDP » sur le formulaire New Nspawn
  pour lui — réactivez-le à la main uniquement si une future version d'Arch restaure le paquet, ou
  si la commande d'installation du modèle est modifiée à la main vers quelque chose qui fonctionne
  (par ex. le propre `krdp` de KDE, toujours dans `extra`, mais lié spécifiquement à KDE/Plasma).
- **Chaque conteneur Fedora a besoin que la vérification PAM de la phase de compte de `sshd` soit
  contournée pour être joignable en SSH du tout.** Chaque tentative de connexion SSH par clé
  publique dans un vrai conteneur Fedora démarré (confirmé sur les versions 43 et 44 — pas
  spécifique à une version) est rejetée avec `Access denied for user <account> by PAM account
  configuration [preauth]` (la phase de compte de `pam_unix`, `pam_acct_mgmt`, renvoie
  `PAM_AUTHINFO_UNAVAIL`) — le compte, son mot de passe, et son `authorized_keys` sont tous
  authentiquement corrects ; `unix_chkpwd` lui-même (l'assistant setuid vers lequel `pam_unix`
  bascule, pour lire `/etc/shadow` en toute sécurité) refuse de s'exécuter avec « This binary is
  not designed for running in this way » — une vérification de légitimité de l'appelant dans les
  `shadow-utils` actuels de Fedora qui ne tolère pas de s'exécuter à l'intérieur d'un conteneur
  `systemd-nspawn`. `UsePAM no` dans `sshd_config` **ne** contourne **pas** ce problème — confirmé
  en direct, le propre processus moniteur privilégié de sshd appelle quand même `do_pam_account`
  sur cette version (sshd lui-même avertit `'UsePAM no' is not supported in this build`). Le
  correctif qui fonctionne : le script pointe la propre phase de compte de `sshd` vers
  `pam_permit.so` (réussit toujours) au lieu du `pam_unix.so` de `password-auth`, uniquement dans
  `/etc/pam.d/sshd` — pas un changement PAM à l'échelle du système. Cela supprime les vérifications
  de phase de *compte* de PAM (expiration, `nologin`, etc.) spécifiquement pour SSH ; la vraie
  vérification d'identité (vérification de clé publique) réussit déjà indépendamment avant que
  cette phase ne s'exécute jamais, donc c'est un compromis étroit et délibéré pour ces comptes
  admin provisionnés jetables. Confirmé fonctionnel en direct sur Fedora 43 ; la version reste
  figée à 43 (pas la plus récente 44) simplement parce que c'est la combinaison exacte vérifiée de
  bout en bout, pas parce que la 44 serait par ailleurs pire.
- **L'invite SSH de chaque conteneur Fedora et Arch était pleine de texte littéral de séquence
  d'échappement** — `start=<uuid>;machineid=<uuid>;user=...;hostname=...;bootid=<uuid>;pid=...;
  type=shell;cwd=...` au lieu d'un simple `[user@host ~]$`. Cause racine (confirmée en direct sur
  Fedora ; Arch a montré le même symptôme et partage la même cause racine, puisque ce n'est pas une
  particularité propre à Fedora — juste selon que le systemd de la distribution soit assez récent
  pour l'embarquer, les deux le sont ici) : systemd 257+ embarque
  `/usr/lib/systemd/profile.d/80-systemd-osc-context.sh` (lié symboliquement dans
  `/etc/profile.d/` par `systemd-tmpfiles`), qui émet une séquence d'échappement OSC 3008
  « Hierarchical Context Signalling » à chaque invite ; le propre émulateur de terminal de
  Guacamole ne la reconnaît/ne la supprime pas, donc elle s'affiche comme texte littéral. Le script
  ne se saute lui-même que lorsque `$TERM` n'est pas défini ou vaut `dumb` (voir son propre
  commentaire d'en-tête), et le client SSH de Guacamole rapporte un vrai `$TERM`, donc il se
  déclenche toujours. Désactivé de la manière documentée (le propre commentaire d'en-tête du script
  donne cette procédure exacte) dans les deux scripts de fabrication : supprimer le lien symbolique
  `/etc/profile.d/` et masquer l'extrait `tmpfiles.d` qui le recrée.
- **Installer le gestionnaire de bureau Xfce sur un conteneur Fedora échouait purement et
  simplement** — `dnf group install -y "Xfce Desktop"` renvoyait une erreur `No match for
  argument: Xfce Desktop`. Confirmé en direct : contrairement à GNOME/KDE, « Xfce Desktop » n'est
  pas du tout un groupe comps sur Fedora actuel (`dnf group list --available` ne le liste pas) —
  Fedora fournit à la place un simple paquet nommé, `xfce4`, qui entraîne tout le bureau. Basculé
  vers un simple `dnf install -y xfce4`, ce qui rend aussi Xfce-sur-DNF pré-récupérable (voir
  « Installation de paquets : téléchargés d'abord » ci-dessus) — contrairement aux installations
  par groupe comps propres de GNOME/KDE, qui ne peuvent toujours pas être pré-récupérées et ont
  toujours besoin du réseau/DNS propre du conteneur pour fonctionner. Au passage, ce même mécanisme
  de pré-récupération a été élargi d'APT uniquement à APT/DNF/PACMAN en général (les scripts de
  téléchargement sous-jacents supportaient déjà les trois ; seule la porte décidant s'il fallait les
  utiliser était encore réservée à APT) — les noms de paquets SSH/RDP/VNC sont désormais aussi
  résolus par gestionnaire de paquets (par ex. le paquet SSH d'Arch est `openssh`, pas
  `openssh-server` ; son installation RDP a en plus besoin de `xorgxrdp`).
- **Cet élargissement de la pré-récupération a alors cassé purement et simplement la création de
  conteneurs Fedora/Arch** — `Failed to download DNF packages [openssh-server] ... dnf: not
  found`, et l'échec identique pour PACMAN. Confirmé en direct sur les deux. Cause racine :
  `nspawnmgr-download-packages-dnf.sh`/`-pacman.sh` (et leurs frères jumeaux de simulation
  d'installation, utilisés par le flux de téléversement de paquets admin) exécutaient `dnf`/
  `pacman` directement sur l'*hôte* (`--installroot=`/`--root=` pointé vers le rootfs du
  conteneur) — fonctionne pour APT, puisque le `.deb` de ce projet ne cible que des hôtes
  Debian/Ubuntu, qui ont toujours `apt-get`, mais ni `dnf` ni `pacman` ne se trouvent jamais sur le
  propre `PATH` d'un tel hôte. Contrairement à la *fabrication* de modèle (qui peut se replier sur
  un `chroot` côté hôte dans un rootfs pas encore démarré), un conteneur en direct, déjà en cours
  d'exécution, ne peut pas être chrooté de la même façon en toute sécurité — le correctif exécute
  plutôt `dnf`/`pacman` *à l'intérieur* du conteneur lui-même via `systemd-run --machine=`, la même
  primitive d'exécution non interactive dans le conteneur que l'étape d'installation réelle utilise
  déjà, téléchargement uniquement donc aucun changement d'état de paquet installé. Compromis :
  DNF/PACMAN perdent la réutilisation propre à APT du type « un paquet déjà en cache, toujours
  valide, n'est jamais retéléchargé » entre conteneurs, puisque le répertoire de cache partagé côté
  hôte n'est pas visible depuis l'intérieur de l'espace de noms de montage propre d'un conteneur —
  chaque pré-récupération DNF/PACMAN retélécharge à neuf.
- **Le correctif dans le conteneur ci-dessus a quand même échoué au premier essai en direct** —
  dnf5 rejette purement et simplement `--destdir` sur `install` (`Unknown argument
  "--destdir=..." for command "install" ... available for: reposync, download, upgrade`) ; la
  combinaison `install --downloadonly --destdir=` de dnf4 ne se reporte pas. La propre commande de
  téléchargement-sans-installation de dnf5 est `download`, et par défaut elle ne récupère que le(s)
  paquet(s) *nommé(s)*, pas leurs dépendances — `--resolve` est ce qui ramène toute la fermeture
  aussi, l'équivalent réel dnf5 de ce que fournissait `install --downloadonly`. Corrigé :
  `dnf download --resolve --destdir=<répertoire> <paquets>`. Même leçon que les bugs
  `groupinstall`→`group install`/EPEL-sur-Fedora ci-dessus : la surface CLI de dnf5 diffère de
  celle de dnf4 de façons réelles et non évidentes — confirmez en direct plutôt que de supposer que
  la syntaxe de l'ère dnf4 se reporte.
- Les deux scripts traduisent aussi le nom d'architecture de `uname -m` (`x86_64`/`aarch64`) vers
  la propre convention d'images.linuxcontainers.org (`amd64`/`arm64`) avant de construire l'URL —
  manquer cette traduction renvoie une 404 quelle que soit par ailleurs la justesse de la
  version/build.
- Les deux scripts réutilisent les mêmes extraits systemd-networkd
  `net.ipv4.ping_group_range`/domaine-DNS dont le script Debian a besoin — ceux-ci concernent la
  propre configuration réseau de conteneur générée par systemd-nspawn, rien de spécifique à Debian,
  donc ils *devraient* se reporter à tout rootfs basé sur systemd, mais c'est une hypothèse, pas un
  fait confirmé en direct, spécifiquement pour Fedora/Arch.

Le propre pré-fetch de dépendances DNF du flux manuel « Install package » (simule via
`dnf install --assumeno`, récupère via `dnf install --downloadonly`) porte la même mise en garde
identique de non-vérification-jusqu'au-test — voir « Téléverser et installer des paquets
arbitraires » ci-dessus.

Alternativement, construisez un modèle Debian à la main via `debootstrap` (la même idée de
récupération de rootfs, si vous préférez ne pas tirer depuis images.linuxcontainers.org, ou voulez
une version/architecture différente) — fabriquez dans un répertoire de travail temporaire, puis
empaquetez-le dans le véritable emplacement `TEMPLATES_DIR` sous forme de tar gzippé :

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

Chaque fichier `.tar.gz` sous `TEMPLATES_DIR/nspawn/` est un modèle sélectionnable ; enregistrez/
modifiez la ligne `Template` correspondante sur `/admin/templates` (admin uniquement) — nom,
identifiant source (le simple radical du nom de fichier, sans `.tar.gz`, sans préfixe de dossier
de backend — par ex. `debian-minimal` pour `TEMPLATES_DIR/nspawn/debian-minimal.tar.gz`), backend,
gestionnaire de paquets, et surcharges optionnelles de commande d'installation. Chaque modèle a un
**backend** (`domain/ContainerBackend.java` : `SYSTEMD_NSPAWN`, `PODMAN`, ou `QEMU`) enregistré
contre lui, chacun avec son propre sous-répertoire `TEMPLATES_DIR` et son propre format de fichier
— voir les sections ci-dessous pour ceux de Podman et QEMU. Une installation fraîche démarre avec
**zéro** modèle — rien n'est pré-rempli — donc cette page (ou le bouton « Configurer
debian-minimal » ci-dessous) est réellement la façon d'obtenir votre premier ; le tarball
lui-même sous `TEMPLATES_DIR` doit toujours être préparé hors bande comme ci-dessus, la page ne
gère que les métadonnées pointant vers lui. Désactiver un modèle (plutôt que de le supprimer) est
la façon normale d'en retirer un — il disparaît de la liste déroulante de création de conteneur
mais les conteneurs existants construits à partir de lui ne sont pas affectés ; la suppression
n'est autorisée qu'une fois qu'aucun conteneur ne le référence plus. Voir la section « Frontière de
confiance » du [§3](#3-le-compte-ssh-doté-des-droits-sudo) pour ce que le contrôle d'accès
admin-uniquement de cette page protège réellement.

**Les modèles peuvent aussi être créés à partir d'une machine existante**, pas seulement
téléchargés à neuf : la propre page de détail d'un conteneur arrêté a un champ « Create template
from this machine » (nom + description optionnelle). Il empaquette le rootfs actuel de cette
machine (`tar -czf`, la même convention que chaque script de fabrication ci-dessus produit déjà)
dans un tout nouveau modèle indépendant — utile pour capturer un instantané d'un conteneur qu'un
propriétaire a déjà personnalisé plutôt que de re-provisionner depuis zéro. Délibérément proposé
uniquement pendant que la machine est **ARRÊTÉE** : empaqueter un rootfs en direct risque une
archive incohérente si des fichiers changent en cours de tar. Contrairement à la page admin
uniquement « New template »/« Set up X-minimal », c'est une action du propriétaire du conteneur
(`/api/containers/{id}/create-template`, pas sous `/api/admin/**`) — le modèle résultant est par
ailleurs identique, y compris la même exigence de mot de passe sudo, et peut ensuite être utilisé
par n'importe qui de la même façon que tout autre modèle. Comme le point d'accès « Install
package », cela ne fonctionne aujourd'hui qu'en mode secret stocké (passe toujours une surcharge de
mot de passe sudo nulle) — le mode approbation admin n'est pas encore câblé pour cette action.

Le champ nom-source du formulaire « New template »/« Edit template » suggère les radicaux simples
de chaque `.tar.gz` déjà présent sous le sous-répertoire de backend sélectionné pour ce modèle
(récupéré depuis `GET /api/admin/templates/available-source-files?backend=...`, soutenu par
`nspawnmgr-list-template-files.sh` — un script wrapper NOPASSWD, en lecture seule, comme
`nspawnmgr-list-machine-images.sh`), pour que vous n'ayez pas à vous souvenir d'un nom de fichier
exact préparé hors bande. C'est une `<datalist>` de navigateur, pas une liste déroulante strictement
restreinte — le champ accepte toujours du texte libre, puisque la liste de suggestions est du
meilleur effort (vide si l'hôte SSH est injoignable ou si le répertoire n'a encore rien dedans) et
ne devrait pas bloquer l'enregistrement des métadonnées d'un modèle avant même que le tarball
n'atterrisse réellement sur le disque.

**Changement cassant :** le stockage des modèles est passé d'un arbre de répertoire extrait en
direct à `TEMPLATES_DIR/<nom>` (cloné via `cp -a`) à un tar gzippé à
`TEMPLATES_DIR/nspawn/<nom>.tar.gz` (cloné via `machinectl import-tar`). Une ligne `Template`
créée avant ce changement pointe vers un emplacement que nspawnmgr ne reconnaît plus — supprimez-la
et recréez-la (par ex. re-cliquez sur « Set up debian-minimal ») ou empaquetez manuellement tout
modèle personnalisé placé à la main vers le nouvel emplacement/format comme montré ci-dessus.

#### Installer/mettre à jour des modèles depuis un pipeline CI/CD

Pour une gestion scriptée des modèles (un pipeline CI/CD construisant et livrant ses propres
modèles) plutôt qu'un humain cliquant à travers `/admin/templates`, nspawnmgr fournit une CLI
invoquée par SSH au lieu d'une API web — cette application n'a aucune authentification HTTP
machine-à-machine du tout (l'authentification Basic et la connexion par formulaire sont toutes
deux explicitement désactivées ; le seul chemin de connexion est le cookie de session soutenu par
votre service d'identité externe), donc un point de terminaison HTTP orienté CI signifierait
inventer un tout nouveau mécanisme d'authentification à partir de zéro. La CLI réutilise plutôt le
modèle de confiance SSH+sudo déjà existant de ce projet.

Cela utilise un **second compte doté des droits sudo, délibérément isolé**, `nspawnmgr_ci` —
séparé de `nspawnmgr_exec` (voir la section « Frontière de confiance » ci-dessous pour savoir
pourquoi). Il n'existe pas tant que vous n'y adhérez pas :

```bash
sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
```

Cela crée le compte, verrouille la connexion par mot de passe (authentification par clé
uniquement), et affiche une clé SSH **privée** fraîchement générée sur stdout exactement une fois
— copiez-la immédiatement dans le propre magasin de secrets de votre système CI ; rien n'est
conservé sur l'hôte au-delà de la moitié publique. Relancez avec `--rotate-key` pour la remplacer
plus tard (l'ancienne clé cesse de fonctionner immédiatement, elle ne traîne pas comme un second
identifiant valide).

Depuis votre pipeline CI/CD, installez ou mettez à jour un modèle (upsert, indexé sur `--name`) en
transmettant le tarball par SSH :

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-template.sh \
  --name my-template --package-manager APT --description "Built by CI" \
  < my-template.tar.gz
```

`--name` devient partie d'un chemin de système de fichiers (`TEMPLATES_DIR/nspawn/<nom>.tar.gz`)
et est validé en conséquence (lettres, chiffres, `-`, `_` uniquement). `--package-manager` est
requis (`APT`, `DNF`, `APK`, ou `PACMAN`) ; `--backend`, `--description`,
`--install-ssh-command`, `--install-xrdp-command`, `--rdp-capable`, `--active` sont tous
optionnels, correspondant aux propres champs et valeurs par défaut du formulaire admin. Le
tarball nouveau/mis à jour n'est mis en place qu'une fois la ligne de base de données confirmée,
donc un échec en cours de route ne laisse jamais un modèle à moitié installé — une mise à jour en
cours laisse la version précédente servir jusqu'à ce que la nouvelle soit entièrement prête.

#### Installer/mettre à jour des paquets depuis un pipeline CI/CD

Le même compte `nspawnmgr_ci` (aucune étape d'adhésion séparée au-delà de celle ci-dessus) peut
aussi publier directement dans le [cache de paquets
admin](#téléverser-et-installer-des-paquets-arbitraires), pour un pipeline CI qui construit ses
propres artefacts `.deb`/`.rpm`/etc. et veut les rendre disponibles aux propriétaires de conteneurs
pour installation sans qu'un humain ne les téléverse à la main :

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-package.sh \
  --package-manager APT --filename my-tool_1.2.3_amd64.deb --description "Built by CI" \
  < my-tool_1.2.3_amd64.deb
```

`--package-manager` (`APT`/`DNF`/`APK`/`PACMAN`/`ISO` — voir [Média
amovible](#média-amovible-images-iso) pour ce que `ISO` signifie ici) et `--filename` sont requis
(ce dernier ne peut pas contenir `/` ni commencer par `.`) ; `--description` est optionnel.
Installer-ou-mettre-à-jour (upsert) est indexé sur `--package-manager` + `--filename` ensemble —
relancer avec les deux mêmes remplace le fichier précédent et met à jour sa ligne en place, même
posture de sécurité anti-crash que les installations de modèles (l'écriture en base de données est
confirmée avant que l'ancien fichier sur disque ne soit remplacé). Comme `cached_packages`
nécessite un vrai compte de téléverseur (`uploaded_by_user_id`), le premier paquet installé par CI
provisionne automatiquement un pseudo-utilisateur dédié `nspawnmgr-ci` — affiché comme le
téléverseur sur la page admin et dans la section « Install package » de chaque conteneur, exactement
comme le propre nom d'utilisateur d'un admin humain le serait.

### Redémarrer les conteneurs

La page de détail d'un conteneur en cours d'exécution a un bouton **Restart** aux côtés de
Stop/Force stop. Il exécute `machinectl reboot` — un redémarrage propre et en place du propre OS
du conteneur, contrairement à Stop+Start : l'enregistrement de la machine et son interface veth ne
sont jamais démontés puis recréés, donc les mappages de ports personnalisés, l'état du pare-feu
d'accès sortant, et tout ce qui d'autre est lié à ce veth restent valides sans avoir besoin d'une
resynchronisation. Le conteneur passe par le même état BOOTING qu'un démarrage frais pendant que
`ContainerReadinessPollingService` attend que SSH (et RDP, si activé) revienne.

### Mettre en pause et reprendre les conteneurs

La page de détail d'un conteneur en cours d'exécution a des boutons **Pause**/**Resume** aux côtés
de Stop/Force stop. Contrairement à Stop, rien n'est démonté : Pause exécute `systemctl freeze`
contre la propre unité `systemd-nspawn@<nom>.service` du conteneur, suspendant chaque processus de
son cgroup en place via le geleur de cgroup du noyau (systemd 246+) ; Resume exécute
`systemctl thaw` pour inverser cela, reprenant exactement là où c'était arrêté. `machinectl`
lui-même n'a aucun concept natif de pause/reprise — c'est l'équivalent moderne et natif à systemd,
le même mécanisme que `systemctl freeze`/`thaw` fournit déjà pour tout autre type d'unité.

Un conteneur démarré via `machinectl start` (ce qui est la façon dont nspawnmgr les démarre
toujours) s'exécute comme l'unité `systemd-nspawn@<nom>.service` directement, sans
`machine-<nom>.scope` séparé — c'est cette unité de service que Pause/Resume ciblent. freeze/thaw
fonctionnent contre toute unité avec un cgroup, unités de service incluses. Le *comportement*
freeze/thaw lui-même (si le contrôleur de gel est disponible/activé, si les processus se
suspendent/reprennent véritablement correctement) vaut toujours la peine d'être confirmé
empiriquement si vous en dépendez fortement.

### Démarrage automatique au démarrage de l'hôte

La page de détail d'un conteneur MANAGED (non affichée pour les hôtes EXTERNAL, qui n'ont pas
d'image `machinectl` propre à activer) a un panneau **Machine settings** avec deux champs :

- **Start automatically when the host boots** — une case à cocher soutenue par
  `systemctl is-enabled`/`enable`/`disable` sur la propre unité
  `systemd-nspawn@<nom>.service` du conteneur.
- **Requires this machine already started** — une liste déroulante du nom de chaque autre
  conteneur MANAGED, soutenue par un extrait d'unité systemd à
  `/etc/systemd/system/systemd-nspawn@<nom>.service.d/nspawnmgr-requires.conf`
  (`Requires=`/`After=` contre la propre unité de la machine choisie, `systemctl daemon-reload`
  après chaque changement). Significatif uniquement aux côtés du démarrage automatique ci-dessus —
  il contrôle *l'ordre* de démarrage entre deux machines qui démarrent toutes deux d'elles-mêmes,
  pas une dépendance d'exécution que Stop/Start impose autrement.

Les deux champs sont **lus en direct depuis l'hôte à chaque chargement de page, pas stockés dans la
propre base de données de nspawnmgr** — délibérément, puisque rien n'empêche un administrateur
d'exécuter `systemctl enable`/`disable` directement sur l'hôte en dehors de nspawnmgr, et une
valeur mise en cache pourrait dériver silencieusement de ce que `systemd` a réellement configuré.
Un accroc SSH transitoire lors de leur lecture affiche un message de repli sur la page plutôt que
de faire échouer celle-ci purement et simplement ; enregistrer un changement passe par les deux
mêmes scripts wrapper que la lecture
(`nspawnmgr-set-machine-autostart.sh`/`nspawnmgr-set-machine-requires.sh`, tous deux NOPASSWD —
routinier, déclenché par le propriétaire, même niveau que Start/Stop).

**La machine `nspawnmgr` auto-hébergée et sa machine de base de données** (voir
[§1](#1-vue-densemble-de-larchitecture)) sont toutes deux configurées pour démarrer
automatiquement de cette façon, avec `nspawnmgr` configuré pour exiger sa machine de base de
données — sinon un redémarrage de l'hôte pourrait faire démarrer `nspawnmgr` avant que sa propre
base de données ne soit accessible. Ceci est câblé par
`ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()` (le même passage de
réconciliation d'infrastructure auto-hébergée qui lie aussi les deux machines au modèle
`debian-minimal`, provisionne leur accès SSH géré, et définit leur description dans la liste de
conteneurs — voir [§1](#1-vue-densemble-de-larchitecture) et [« Découvrir des machines créées en
dehors de nspawnmgr »](#découvrir-des-machines-créées-en-dehors-de-nspawnmgr)), qui s'exécute selon
son propre calendrier récurrent d'environ 30 s dès que la propre application Spring de nspawnmgr
démarre — pas conditionné à une quelconque action admin. Un échec transitoire (journalisé en WARN,
jamais fatal) est simplement repris au passage suivant, aucune action admin nécessaire ; la même
réconciliation s'exécute aussi toujours dans le cadre d'un clic manuel sur **Discover machines**.

### Mise en réseau des conteneurs

Chaque conteneur géré partage un seul pont, `nspawnbr0` (`Bridge=nspawnbr0` dans le fichier
`.nspawn` généré — `machinectl start` asservit automatiquement le propre veth de chaque conteneur
dedans au démarrage), plutôt que chacun obtenant un veth point-à-point isolé sur son propre
sous-réseau privé. `nspawnbr0` et son adresse (`10.100.0.1/24`, fixe et non configurable par
l'admin — une convention interne, pas un véritable point de personnalisation) sont créés
inconditionnellement par le propre postinst du `.deb`
(`/etc/systemd/network/70-nspawnmgr-bridge.netdev`/`.network`), pas quelque chose que vous
configurez à la main. **Network diagnostics** a une vérification en lecture seule confirmant qu'il
est effectivement actif.

**SSH/RDP/VNC n'ont besoin d'aucune redirection entrante du tout.** Le `guacd` de Guacamole et le
propre sondage de disponibilité de nspawnmgr composent tous deux directement l'adresse veth
interne d'un conteneur MANAGED (son interface `host0`, résolue en direct via `machinectl`/`nsenter`
— voir `nspawnmgr-get-internal-address.sh`), sur le vrai port sshd/xrdp/VNC du conteneur
(22/3389/5900). Il n'y a aucune redirection de port hôte dans la boucle du tout pour ceux-ci, ce
qui contourne une limitation de hairpin-NAT sur le même hôte confirmée sur du matériel réel : le
trafic depuis l'hôte lui-même repassant par sa propre adresse DNAT'ée/redirigée vers un conteneur
n'est fréquemment pas re-NATé correctement, même si un client véritablement externe atteignant
cette même adresse+port fonctionne bien. L'adresse interne assignée du conteneur est journalisée
(en INFO) au moment où il atteint RUNNING, et resynchronisée vers la configuration de connexion de
Guacamole à chaque redémarrage suivant au cas où l'adresse change.

### Accès graphique : RDP, VNC et gestionnaires de bureau

Le formulaire « New Nspawn » a deux cases à cocher indépendantes, **Enable RDP** et **Enable VNC**
— l'une, les deux, ou aucune. Choisir l'une ou l'autre révèle une liste déroulante **Desktop
manager** (None/GNOME/KDE (`kde-standard`)/Xfce (`xfce4`)) : un protocole graphique n'a qu'une
utilité limitée sans un véritable environnement de bureau à l'intérieur d'un modèle minimal, donc
en choisir un l'installe pendant le provisionnement, partagé entre RDP et VNC si les deux sont
choisis. **None** signifie que rien de supplémentaire n'est installé.

Contrairement à l'accès par identifiants-à-la-demande couvert ci-dessous, RDP/VNC choisis au
moment de la création obtiennent un vrai compte/mot de passe généré que nspawnmgr crée et stocke
(RDP réutilise le compte SSH avec un mot de passe de connexion défini via `chpasswd` ; VNC réutilise
le même compte mais ne définit qu'un mot de passe spécifique à VNC via `vncpasswd` — il n'a besoin
d'aucun mot de passe de connexion Linux propre). La séquence exacte
`vncserver`/`xstartup`/installation-de-paquet n'a été exercée que contre le seul vrai modèle
`debian-minimal` (APT) en usage actif — cela vaut la peine d'être reconfirmé après avoir installé
un `.deb` qui inclut ceci.

### Podman : pods

Aux côtés des conteneurs nspawn, l'élément **New Pod** du menu « + » crée un vrai conteneur exécuté
par `podman` (badge `PODMAN` sur la grille Machines, aux côtés de `NSPAWN`/`QEMU`/`HOST`) — mêmes
règles de propriété/partage, même grille de cartes, même relation de page de détail que tout le
reste ici. Disponible pour tout utilisateur connecté, pas réservé aux admins ; le lien est
uniquement désactivé tant qu'aucun modèle avec backend podman n'existe encore, même posture que
New Nspawn.

**Création** (`/containers/new-pod`) : Nom, Template (une liste déroulante des seuls modèles avec
backend podman), Description, et une Command optionnelle — comme une surcharge de `CMD` Dockerfile
; la laisser vide fait confiance à la propre commande intégrée de l'image. Un simple shell
interactif comme commande sortira en quelques instants une fois que plus rien n'est attaché à son
stdin, faisant atterrir le pod STOPPED plutôt qu'échoué — cela vaut la peine de le savoir si un
premier pod semble disparaître immédiatement après création. Le provisionnement
(`ProvisioningService.provisionPod()`) charge l'image du modèle, crée et démarre le conteneur,
accorde l'accès au propriétaire, résout et persiste son adresse interne, et le fait atterrir
directement à **RUNNING** — contrairement aux conteneurs nspawn, il n'y a pas de phase
BOOTING/sondage-de-disponibilité, puisque `podman create`+`start` sont synchrones et qu'un pod
n'obtient aucun identifiant SSH auto-provisionné à sonder en premier lieu.

**Mise en réseau** : les pods partagent le même pont `nspawnbr0` que les conteneurs nspawn, mais à
travers une définition de réseau podman dédiée (`/etc/containers/networks/nspawnbr0.json`, écrite
par `nspawnmgr-configure-podman-network.sh`) utilisant l'**IPAM host-local** de netavark plutôt que
le DHCP — le propre proxy DHCP de netavark émet depuis l'espace de noms réseau de l'hôte, et le
noyau ne reboucle jamais ce trafic vers la propre file de réception du pont, une impasse confirmée
plutôt qu'une option inexplorée. Le pool d'adresses est séparé de la propre plage DHCP de nspawn
pour éviter les collisions : les pods obtiennent `10.100.0.192`–`10.100.0.254`, les conteneurs
nspawn gardent `10.100.0.2`–`10.100.0.191`. Le DNS est défini explicitement à la création
(`podman create --dns 10.100.0.1 --dns-search internal ...`) plutôt que de se fier à une
configuration livrée par DHCP qu'un pod n'obtient jamais — le propre `aardvark-dns` de podman est
désactivé sur ce réseau spécifiquement pour éviter de se battre avec le propre dnsmasq de
nspawnmgr, déjà lié à cette même adresse (voir [« Résolution des conteneurs par
nom »](#résolution-des-conteneurs-par-nom) ci-dessus).

**Le cycle de vie** a une parité complète avec les conteneurs nspawn — Start/Stop/Restart/Pause/
Resume envoient tous vers des commandes podman natives (`start`/`stop`/`kill`/`restart`/
`pause`/`unpause`) plutôt que vers un quelconque mécanisme spécifique à nspawn. Un
**`ContainerLivenessPollingService`** séparé revérifie le vrai statut podman de chaque pod
RUNNING (et le vrai statut d'unité de chaque VM QEMU RUNNING — voir ci-dessous) selon son propre
calendrier d'environ 30 s et bascule le propre état de nspawnmgr vers `STOPPED` dès que la réalité
diverge — nécessaire parce qu'un pod peut se terminer entièrement de lui-même (une commande de
maintien en vie manquante ou défectueuse, voir le champ Command ci-dessus) sans que rien d'autre
dans l'application ne le remarque jamais, puisque les pods sautent entièrement le chemin de
sondage-de-disponibilité propre à nspawn. Les pods `PAUSED` ne sont pas sondés.

**Accès** : SSH/RDP/VNC sont **par identifiants-à-la-demande uniquement**, le même mécanisme
conditionné par la joignabilité que les Hosts et les conteneurs découverts utilisent
([§ ci-dessus](#accès-distant-pour-les-conteneurs-que-nspawnmgr-na-pas-configurés-lui-même)) —
activé par protocole depuis la propre page de détail du pod une fois que le propre service de
l'invité écoute réellement. Un pod n'obtient jamais d'identifiant auto-généré de la façon dont
l'accès SSH d'un conteneur nspawn le fait.

**Files** fonctionne via `podman mount`, qui expose le système de fichiers overlay fusionné du
conteneur comme un simple chemin hôte — le même code de navigation/téléversement/téléchargement
que les conteneurs nspawn utilisent s'exécute alors directement contre ce chemin.

**Scripts** s'exécutent via `podman exec -i <nom> sh -s` (stdin transmis par pipe, un vrai code de
sortie renvoyé à nspawnmgr). Abort est une approximation plus étroite que le propre kill d'unité
transitoire de nspawn : le corps du script est préfixé avec `echo $$ > <fichier-pid>`, et Abort
envoie `kill -9` à ce groupe de processus enregistré — un vrai kill de groupe de processus, mais
pas un vrai kill à l'échelle du cgroup de la façon dont l'abort de nspawn l'est, documenté dans le
code comme un rétrécissement connu et délibéré plutôt qu'un bug.

**Explicitement non proposé pour un pod** (tous présents pour les conteneurs nspawn) : aucun
identifiant SSH/RDP/VNC auto-provisionné, aucune installation de gestionnaire de bureau, aucun
mappage de port entrant personnalisé, aucun bascule de pare-feu sortant (un pod a déjà un vrai
accès réseau via netavark — il n'y a rien à contrôler), aucun montage ISO, aucune configuration de
type démarrage-automatique/exigences façon `machinectl`.

**Les modèles** vivent sous `TEMPLATES_DIR/podman/<nom>.tar` — une archive `podman save`, chargée
via `podman load` au moment de la création, distincte de la convention de simple tar de nspawn.
Peuplez-en un soit en tirant directement depuis un registre
(`nspawnmgr-podman-pull-template.sh`) soit en convertissant un modèle nspawn existant
(`nspawnmgr-podman-convert-nspawn-to-podman.sh`, et l'inverse,
`nspawnmgr-podman-convert-podman-to-nspawn.sh`, pour aller dans l'autre sens). Il n'y a
actuellement pas de commodité « create template from this pod » de la façon dont la propre page de
détail d'une machine nspawn ou QEMU arrêtée le propose — seulement des tirages ou conversions
fraîches.

Aucune suite de tests automatisés dédiée n'existe pour le backend podman (aucune classe de test
`*Podman*`) — il est couvert par la suite de tests générale s'exécutant contre des simulations,
plus le test manuel du dev-stack et le clic-à-travers en direct sur yoga. Le correctif DNS et la
décision de mise en réseau IPAM host-local de netavark ci-dessus sont tous deux confirmés en direct
(voir les propres commentaires d'en-tête de `nspawnmgr-configure-podman-network.sh` et de
`nspawnmgr-podman-create-container.sh`) — l'approximation d'abort par kill de groupe de processus
est le principal écart connu et délibéré.

### QEMU : machines virtuelles

Aux côtés des conteneurs nspawn et des pods podman, l'élément **New QEMU** du menu « + » crée une
vraie machine virtuelle QEMU/KVM (badge `QEMU`), sur la même grille Machines avec les mêmes règles
de propriété/partage. Disponible pour tout utilisateur connecté ; le lien est désactivé tant que
QEMU n'est pas installé sur l'hôte (voir la page Diagnostics).

**Création** (`/containers/new-qemu`) : Nom ; source de disque — **Empty disk** (une taille en Go)
ou **From template** (cloner le propre disque d'un Template existant avec backend QEMU), mutuellement
exclusifs ; **Processor type** ; **Number of CPUs** ; **Memory (MB)** ; **Network card** (modèle
d'appareil NIC — `virtio-net-pci` par défaut, ou `e1000`/`rtl8139`/`pcnet` pour les OS invités qui
ont besoin d'un spécifique, par ex. FreeDOS a typiquement besoin de `pcnet`) ; **Pointer device**
(`PS/2` par défaut, ou `USB tablet`, qui corrige la dérive du curseur de souris sous VNC pour les
invités graphiques — mais les invités de la famille DOS n'ont aucune pile de pilote USB du tout et
ont besoin de PS/2, ce pourquoi cela reste la valeur par défaut plutôt que USB tablet) ; et un
**Boot ISO** optionnel.

`POST /api/containers/qemu` valide qu'exactement un des champs taille-de-disque/modèle est défini,
puis `ProvisioningService.createPendingQemu()` persiste la ligne et `provisionQemu()` fait le
travail réel : cloner le disque du modèle ou en créer un neuf vide, allouer un port VNC, écrire
l'unité systemd de la VM, la démarrer, générer et stocker un mot de passe VNC, et créer une
connexion VNC Guacamole correspondante — atterrissant à **RUNNING** immédiatement, le même
raisonnement de lancement synchrone que les pods ci-dessus (aucun sondage BOOTING/disponibilité).
Un `QemuAddressPollingService` séparé et asynchrone essaie ensuite de résoudre une IP invitée
purement à des fins SSH — « pas encore prêt, peut-être pour longtemps » est l'état normal et
attendu pour une VM fraîchement créée qui pourrait même ne pas encore avoir d'OS invité installé
sur son disque.

**Création de disque** (`nspawnmgr-qemu-create-disk.sh`) est un simple
`qemu-img create -f qcow2 <chemin> <taille>G` sous `/var/lib/nspawnmgr/qemu-disks/`. Même sudo de
niveau MOT DE PASSE que tout autre nouvel artefact persistant
([§3](#3-le-compte-ssh-doté-des-droits-sudo)) — démarrer réellement la VM ensuite est une étape
NOPASSWD séparée.

**L'unité systemd de la VM** (`nspawnmgr-qemu-write-unit.sh`) est une vraie unité persistante à
`/etc/systemd/system/nspawnmgr-qemu-<nom>.service` — réécrite, pas seulement écrite une fois, à la
fois à la création et à nouveau chaque fois que l'ISO monté change pendant que la VM est arrêtée
(voir ci-dessous). Elle est persistante plutôt qu'une invocation `systemd-run` transitoire parce
qu'un simple `systemctl start/stop` contre elle (ce qui est la façon dont nspawnmgr pilote toujours
le cycle de vie d'une VM QEMU) ne prend qu'un simple nom de machine, sans rien de spécifique à la
VM à reconstruire à partir d'une invocation. Sa ligne `ExecStart` couvre : les drapeaux
mémoire/modèle-CPU/nombre-de-CPU/`-enable-kvm` (KVM auto-détecté via l'existence de `/dev/kvm`) ;
le disque qcow2 comme lecteur virtio ; la carte réseau sur `nspawnbr0` avec une adresse MAC dérivée
de façon déterministe du nom de la VM (`52:54:00:` + les 3 premiers octets d'un hachage md5 du nom
— le script de résolution d'adresse doit dériver la même valeur identique indépendamment, puisque
ni l'un ni l'autre script ne la persiste) ; les drapeaux de dispositif de pointage (vide pour PS/2,
`-usb -device usb-tablet` pour USB tablet) ; l'écouteur VNC ; un moniteur QEMU sur socket Unix ; et
l'ordre de démarrage (`-cdrom ... -boot order=d` quand un ISO est monté, `-boot order=c` sinon). Se
replie sur `/usr/libexec/qemu-kvm` quand `qemu-system-x86_64` n'est pas sur `PATH` (une
particularité d'empaquetage Fedora/RHEL, même repli que `nspawnmgr-diag-check-qemu.sh` utilise
déjà).

**Accès VNC** : le port est alloué depuis une plage configurable par l'admin
([`/admin/settings`](#paramètres-modifiables-en-direct-adminsettings), validée pour commencer à
`5900` ou au-dessus — la propre syntaxe `-vnc host:display` de QEMU adresse un numéro d'affichage,
et `display = port - 5900`), en choisissant le port libre le plus bas pas déjà réclamé par une
autre VM. L'écouteur se lie toujours à la propre adresse de passerelle de `nspawnbr0`
(`10.100.0.1`) — contrairement à nspawn/podman, où Guacamole compose directement la propre adresse
interne d'un conteneur, la console hyperviseur de chaque VM QEMU partage une seule adresse et n'est
différenciée que par port. Une connexion VNC Guacamole avec un mot de passe généré est créée
automatiquement au moment du provisionnement — rien pour le propriétaire à activer, elle est
simplement là. QEMU lui-même ne persiste pas ce mot de passe à travers un redémarrage, donc
`ContainerLifecycleService` réapplique l'identifiant stocké par-dessus le moniteur HMP (voir
ci-dessous) à chaque démarrage/redémarrage.

**Le moniteur HMP** est interne uniquement — il n'y a pas d'interface pour envoyer des commandes
de moniteur arbitraires. `nspawnmgr-qemu-monitor-exec.sh` relaie une ligne HMP à la fois par SSH
vers le socket Unix moniteur de la VM via `socat` (fermant la connexion 2 secondes après que QEMU
cesse de répondre, puisque le REPL en texte brut de HMP n'a pas de découpage propre par réponse
pour détecter l'achèvement — un point de départ, documenté comme pas encore vérifié contre un vrai
moniteur `qemu-system-x86_64`). Il soutient : l'arrêt propre (`system_powerdown`, une requête
ACPI — un no-op si aucun OS invité n'est encore installé, par conception, pas un bug) ;
Pause/Resume (`stop`/`cont` — le propre équivalent de QEMU, pas le geleur de cgroup que les
conteneurs nspawn utilisent) ; la réapplication du mot de passe VNC ci-dessus ; et l'échange d'ISO
en direct (`change ide1-cd0`/`eject ide1-cd0`).

**L'accès Files n'est pas disponible pour une VM QEMU** — contrairement au `podman mount` de
podman, il n'y a pas de répertoire côté hôte à parcourir pour une VM dont le stockage est un
unique fichier de disque qcow2, et l'accès réel côté invité (SFTP par-dessus la propre connexion
SSH de la VM, une fois activée) n'a pas encore été construit. La pastille FILES est désactivée sur
la carte d'une VM QEMU pour cette raison ; prévu pour une future version.

**Le montage ISO** réutilise le même cache de paquets `PackageManager.ISO` que les conteneurs
nspawn ([§ ci-dessus](#média-amovible-images-iso)). Contrairement au montage bind statique de
nspawn (qui ne prend effet qu'au prochain démarrage de la VM), QEMU peut **échanger en direct** le
disque monté à travers le moniteur HMP pendant que la VM est actuellement en cours d'exécution, et
persiste séparément le même choix dans le fichier d'unité (via la même réécriture de
`nspawnmgr-qemu-write-unit.sh` mentionnée ci-dessus) pour que ce soit aussi correct la prochaine
fois que la VM démarre à froid.

**Les modèles** : cloner le disque d'une VM depuis un Template existant avec backend QEMU
(`TEMPLATES_DIR/qemu/<nom>.qcow2`) est entièrement supporté aux côtés du chemin
disque-vide-plus-ISO décrit ci-dessus — choisissez **From template** sur le formulaire New QEMU.
La propre page de détail d'une VM arrêtée a aussi un champ « Create template from this machine »,
la même convention que les conteneurs nspawn utilisent, pour capturer un instantané du disque
actuel d'une VM dans un tout nouveau modèle indépendant.

**Le cycle de vie** a une parité complète avec nspawn/podman à travers l'unité systemd persistante
ci-dessus, plus le moniteur HMP pour les opérations que QEMU lui-même doit se voir demander de
faire gracieusement : Start, Force stop, et Restart sont de simples `systemctl start/stop/restart`
contre la propre unité de la VM ; l'arrêt propre et Pause/Resume passent par HMP comme décrit
ci-dessus plutôt que par `systemctl freeze`/`thaw`.

**Réconciliation de plantage** : le même `ContainerLivenessPollingService` décrit ci-dessus pour
podman couvre aussi QEMU — la propre unité de chaque VM RUNNING est revérifiée
(`systemctl is-active`) selon le même calendrier d'environ 30 s, et le propre état de nspawnmgr
bascule vers `STOPPED` dès que l'unité elle-même s'est arrêtée ou a disparu sous ses pieds.
**Toujours une véritable limite, pas entièrement résolue** : cela ne détecte que la disparition de
l'unité/du processus lui-même, pas un plantage uniquement au niveau de l'OS invité où le processus
reste en vie mais où ce qui s'exécute à l'intérieur s'est figé ou est mort — `systemctl is-active`
n'a aucune visibilité là-dessus, et aucun des deux backends n'offre de moyen de le demander. Cela
vaut la peine d'être gardé à l'esprit si le badge d'une VM semble jamais en désaccord avec la
réalité malgré le processus techniquement toujours en cours d'exécution.

Aucune suite de tests automatisés dédiée n'existe non plus pour le backend QEMU (aucune classe de
test `*Qemu*`) — couvert par la suite générale contre des simulations, plus le test manuel du
dev-stack et le clic-à-travers en direct ; le paramètre de dispositif de pointage spécifiquement a
été confirmé en direct contre une vraie VM KolibriOS sur yoga. L'heuristique de découpage de
réponse du moniteur HMP ci-dessus, et certaines des propres vérifications de
`nspawnmgr-diag-check-qemu.sh`, sont explicitement marquées non vérifiées contre un vrai moniteur
`qemu-system-x86_64` dans leurs propres commentaires d'en-tête.

**Discover machines** ([§ ci-dessus](#découvrir-des-machines-créées-en-dehors-de-nspawnmgr)) couvre
les trois backends en un clic — il exécute un passage séparé sur `machinectl`, `podman`, et les
propres unités systemd de QEMU chacun, enregistrant tout ce qu'il trouve de non suivi dans l'un ou
l'autre, sautant purement et simplement un backend s'il n'est pas installé sur l'hôte du tout.

### Installation de paquets : téléchargés d'abord, jamais installés directement depuis une récupération réseau à chaud

Un gestionnaire de paquets exécuté *depuis l'intérieur* d'un conteneur en cours d'exécution s'est
révélé peu fiable pour résoudre ses propres miroirs, même quand le propre réseau/DNS de l'hôte
fonctionne bien. SSH, RDP, VNC, et le paquet gestionnaire de bureau reçoivent tous le même
traitement : nspawnmgr les télécharge (avec leur fermeture de dépendances complète,
téléchargement uniquement — rien n'est encore installé) avant d'exécuter l'installation réelle
*à l'intérieur* du conteneur. S'applique aux modèles **APT, DNF, et PACMAN** utilisant les
commandes d'installation par défaut (non surchargées) — une surcharge de commande d'installation
personnalisée ne peut pas être analysée en toute sécurité pour en extraire des noms de paquets à
pré-récupérer, et se replie sur l'installation dans-le-conteneur-uniquement d'aujourd'hui (qui a
besoin que le propre réseau/DNS du conteneur fonctionne réellement). **APK** est exclu entièrement
: sa propre installation locale résout déjà les dépendances depuis des dépôts configurés d'elle-même,
aucune pré-récupération nécessaire (sans importance de toute façon — les conteneurs basés sur
Alpine ne fonctionnent pas entièrement dans cette application aujourd'hui, voir ci-dessous).

**La propre étape de téléchargement d'APT s'exécute côté hôte** — un processus pointé directement
vers le propre répertoire rootfs du conteneur (`apt-get -o Dir=<rootfs>`), utilisant le propre
réseau fonctionnel de l'hôte — puisque `apt-get` est toujours sur le propre `PATH` de cet hôte (le
`.deb` de ce projet ne cible que Debian/Ubuntu). **DNF et PACMAN ne peuvent pas faire cela** :
aucun des deux ne se trouve jamais sur le propre `PATH` de cet hôte du tout, donc leur propre
étape de téléchargement s'exécute plutôt *à l'intérieur du conteneur lui-même*, via
`systemd-run --machine=` (la même primitive d'exécution non interactive dans le conteneur que
l'étape d'installation réelle utilise déjà) — téléchargement uniquement, comme APT, donc cela ne
touche toujours pas l'état de paquet installé de dpkg/rpm/pacman. Une conséquence : DNF/PACMAN
n'obtiennent pas la réutilisation propre à APT du type « un paquet déjà en cache, toujours valide,
n'est jamais retéléchargé » entre conteneurs (cela repose sur un simple répertoire de cache côté
hôte que dnf/pacman s'exécutant *à l'intérieur* de l'espace de noms de montage propre d'un
conteneur ne peut pas voir) — chaque pré-récupération DNF/PACMAN retélécharge à neuf. Les trois
mettent quand même en cache la fermeture sous `/var/cache/nspawnmgr/packages/<gestionnaire>/auto/`
pour la visibilité de la page admin Packages, quel que soit l'endroit où le téléchargement lui-même
s'est exécuté.

Une exception : GNOME/KDE sur DNF s'installe via un *groupe* comps (`dnf group install`), pas un
simple paquet nommé — `dnf --downloadonly` (ce que la pré-récupération utilise) n'a pas d'équivalent
pour résoudre/mettre en cache l'appartenance d'un groupe entier à l'avance, seulement des paquets
individuels, donc ces deux combinaisons sautent délibérément la pré-récupération et retombent
directement sur l'installation de groupe dans-le-conteneur (nécessitant le propre réseau/DNS du
conteneur, comme une commande surchargée le ferait). Xfce n'a pas ce problème — confirmé en direct,
Fedora l'expédie comme un simple paquet nommé (`xfce4`), pas du tout comme un groupe comps.

Cette étape d'installation réelle dans-le-conteneur ne relance jamais le propre rafraîchissement de
métadonnées d'`apt-get update`/`dnf` : c'est redondant, puisque l'étape de pré-téléchargement a déjà
rafraîchi l'index (côté hôte pour APT, dans-le-conteneur pour DNF/PACMAN) quelques instants plus
tôt, donc ce que l'étape d'installation lit est déjà frais, et chaque paquet dont elle a besoin est
déjà assis dans le propre cache local du conteneur — chaque script de pré-récupération y laisse une
copie exactement pour cette raison.

Le paquet de premier niveau lui-même (pas ses dépendances transitives, qui restent un détail
d'implémentation du répertoire de cache) est aussi enregistré dans le cache admin **Packages**
décrit juste ci-dessous, donc ce que nspawnmgr a récupéré pour son propre provisionnement est
visible et réutilisable là aussi, pas juste un effet secondaire caché de la création d'un
conteneur.

### Téléverser et installer des paquets arbitraires

Les administrateurs peuvent aussi téléverser directement n'importe quel fichier de paquet :
**Packages** (depuis la liste de conteneurs, admin uniquement) accepte un fichier
`.deb`/`.rpm`/quel-que-soit-ce-qu'utilise-votre-gestionnaire-de-paquets plus une description
optionnelle. Chaque propriétaire de conteneur voit alors une section correspondante **Install
package** sur la propre page de détail de son conteneur (seuls les paquets pour le propre
gestionnaire de paquets de ce conteneur sont proposés) — en choisir un et cliquer sur Install le
copie sur le conteneur, puis, pour les paquets **APT, DNF, et PACMAN**, *simule* d'abord
l'installation (`apt-get install -s` / `dnf install --assumeno` / `pacman -U --print`, aucun
changement effectué) contre le propre état du conteneur pour trouver toute dépendance qu'il n'a
pas déjà. Tout ce qui manque est récupéré de la même façon que le provisionnement
SSH/RDP/VNC/gestionnaire-de-bureau le fait déjà (voir ci-dessus — côté hôte pour APT, à
l'intérieur du conteneur lui-même via `systemd-run --machine=` pour DNF/PACMAN, puisqu'aucun des
deux ne se trouve jamais sur le propre `PATH` de cet hôte) et enregistré ici dans le cache de
paquets aussi, puis l'installation réelle s'exécute via la propre commande d'installation locale de
fichier du gestionnaire de paquets (`apt-get install <chemin>` / `dnf install <chemin>` /
`pacman -U --noconfirm <chemin>`) — sa propre résolution de dépendances récupère à la fois le
fichier téléversé et ce qui vient d'être pré-récupéré en un seul passage cohérent. La propre
installation locale de DNF/PACMAN résoudrait normalement les dépendances directement depuis
l'accès réseau propre du conteneur, comme l'un ou l'autre le fait pour tout paquet nommé — l'étape
de pré-récupération s'exécute quand même, délibérément, pour la cohérence avec la propre posture
d'APT de « ne jamais laisser un conteneur atteindre le réseau directement pour une recherche de
miroir de gestionnaire de paquets en direct » (la propre pré-récupération de DNF/PACMAN a quand
même besoin du réseau du conteneur pour le téléchargement dans-le-conteneur lui-même — elle
contient juste ce besoin à une seule étape non interactive, téléchargement uniquement, au lieu de
la vraie commande d'installation). Cette sous-étape nécessite le même niveau de mot de passe sudo
que la création de conteneur, donc elle échoue purement et simplement (aucune installation
partielle silencieuse) si aucun secret sudo stocké n'est configuré et que la requête n'en a pas
fourni un. **Le support DNF et PACMAN pour installer un paquet téléversé *dans un conteneur
Fedora/Arch* n'est pas vérifié** — distinct d'installer *nspawnmgr lui-même* sur un vrai hôte
RPM/Arch, ce qui est vérifié (voir les sections d'installation des paquets RPM et Arch
ci-dessus) ; ce flux spécifique de téléversement de paquet dans-le-conteneur n'a jamais été exercé
contre un vrai conteneur Fedora/Arch, seulement construit selon le contrat CLI documenté de chaque
outil aussi soigneusement que possible — signalez toute divergence en direct trouvée. **PACMAN est
le plus spéculatif des deux** : contrairement à `apt-get install -s`/`dnf install --assumeno`, qui
sont les propres modes d'essai à blanc bien documentés d'apt/dnf, le comportement de
`pacman -U --print` pour une simulation complète de fermeture de dépendances de fichier local n'a
jamais été exercé nulle part dans ce projet, pas même manuellement. Les paquets **APK** sautent
tout cela et exécutent simplement une seule installation locale (`apk add <chemin>`) sans
résolution de dépendances — une dépendance manquante y reste une erreur visible dans la sortie, pas
corrigée automatiquement (la propre installation locale d'APK résoudrait effectivement les
dépendances depuis des dépôts configurés, mais les conteneurs basés sur Alpine ne fonctionnent pas
entièrement dans cette application aujourd'hui de toute façon — voir ci-dessous). Les paquets que
nspawnmgr a auto-téléchargés (soit pour son propre provisionnement SSH/RDP/VNC/gestionnaire-de-
bureau, soit comme dépendance récupérée par ce flux) apparaissent ici aussi, attribués à quel que
soit le conteneur dont la création ou l'installation les a récupérés en premier, aux côtés de tout
ce qu'un admin a téléversé à la main.

Le bouton **« Show transitive dependencies »** de la page Packages comble l'écart que cela laisse
délibérément : choisissez un gestionnaire de paquets (APT/DNF/PACMAN, les trois mêmes ayant un
répertoire de cache de pré-récupération du tout) et il liste chaque fichier réellement assis dans
le propre répertoire partagé `/var/cache/nspawnmgr/packages/<gestionnaire>/auto` de ce
gestionnaire, avec la taille en octets. Ceci est généré à neuf en exécutant une commande et en
lisant le vrai répertoire à chaque fois que le bouton est cliqué (`nspawnmgr-list-auto-cache.sh`,
un script wrapper NOPASSWD en lecture seule) — rien à son sujet n'est stocké dans la base de
données, contrairement aux paquets de premier niveau dans le tableau ci-dessus. Utile pour
confirmer qu'une dépendance a effectivement atterri sur le disque, ou pour évaluer d'un coup d'œil
combien de ce répertoire de cache partagé un gestionnaire de paquets donné a accumulé au fil du
temps.

### Média amovible (images ISO)

**ISO** est une vraie valeur `PackageManager`, pas un cache/entité/page-admin séparé — téléversez-en
une depuis la même page admin **Packages** tout comme un `.deb`/`.rpm`, en choisissant `ISO` au lieu
de `APT`/`DNF`/`APK`/`PACMAN`. La machinerie d'installation de style `.deb`/`.rpm` ne s'applique pas
à elle (il n'y a pas de commande d'installation pour `ISO`, et `Template.packageManager` ne peut
jamais valoir `ISO` — la propre liste déroulante du formulaire admin Templates l'exclut), mais le
chemin de téléversement/cache/publication-CI est identique de toute façon, un choix délibéré plutôt
que de construire une seconde voie parallèle. Tout propriétaire de conteneur peut ensuite configurer
un ISO téléversé depuis la section « Removable media » de la propre page de détail de son conteneur
— au plus un par conteneur à la fois, comme un vrai lecteur CD, toujours monté en lecture seule au
`/mnt/cdrom` fixe. Monter un ISO différent alors qu'un autre est déjà configuré éjecte
automatiquement l'ancien d'abord ; il n'y a pas d'étape éjecter-puis-monter séparée.

**Un paramètre persistant et déclaratif — exactement comme les [mappages de ports
personnalisés](#mappages-de-ports-personnalisés-et-accès-sortant), pas une opération en direct.**
Monter/éjecter réécrit immédiatement le fichier `.nspawn` du conteneur (une ligne statique
`[Files]` `BindReadOnly=`), mais ne prend effet que la prochaine fois que le conteneur est
(re)démarré, et reste configuré à travers les redémarrages jusqu'à être explicitement changé ou
éjecté — cela n'exige *pas* que le conteneur soit en cours d'exécution pour être configuré, et un
arrêt/redémarrage ne le vide *pas*. La moitié côté hôte (un fichier ISO monté en boucle à un chemin
fixe par conteneur, `nspawnmgr-mount-iso.sh`/`nspawnmgr-unmount-iso.sh`) est mise en place/démontée
dès que vous montez/éjectez, indépendamment du fait que le conteneur soit ou non en cours
d'exécution à ce moment-là ; un redémarrage de l'hôte, cependant, ne rétablit pas actuellement ce
montage en boucle de lui-même, donc un conteneur démarré après un redémarrage de l'hôte avec un ISO
encore configuré échouera à démarrer jusqu'à ce que cela soit corrigé à la main
(`mount -o loop,ro <iso> /var/lib/nspawnmgr/iso-mounts/<nom>`) — une limitation connue, pas
automatiquement réconciliée aujourd'hui.

**Cela fait de `systemd-networkd` un prérequis absolu, pas seulement un agrément pour l'accès
sortant** — le propre postinst de nspawnmgr l'utilise pour créer et configurer `nspawnbr0`
lui-même (voir ci-dessus), et le propre contrôle de disponibilité de nspawnmgr ainsi que `guacd`
composent tous deux directement l'adresse `host0` d'un conteneur une fois qu'il en a une, donc un
conteneur qui n'en obtient jamais une (`host0` jamais activé à l'intérieur du modèle — voir l'étape
2 ci-dessous) ne quitte jamais BOOTING, purement et simplement, pas juste lentement. Vérifiez
n'importe lequel de vos propres modèles pour `systemctl enable systemd-networkd` si les conteneurs
cessent d'atteindre RUNNING.

La seule redirection entrante restante au niveau hôte est les [mappages de ports
personnalisés](#mappages-de-ports-personnalisés-et-accès-sortant) — entièrement optionnels, gérés
par le propriétaire, et utilisant le même mécanisme `.nspawn`
`Port=tcp:<port-hôte>:<port-conteneur>` (que `systemd-nspawn` met toujours en place automatiquement
sous forme de règles DNAT au démarrage).

Concrètement, pour finir de mettre cela en place :

1. `sudo systemctl enable --now systemd-networkd` (**Network diagnostics** a une vérification +
   un correctif en un clic pour cela), et `sudo sysctl -w net.ipv4.ip_forward=1` (persistez-le sous
   `/etc/sysctl.d/`) — `IPMasquerade=yes` dans le propre fichier `.network` de `nspawnbr0` (voir
   ci-dessus) ajoute la règle NAT, mais le transfert de paquets réel entre interfaces est un
   paramètre séparé, à l'échelle du noyau, que ce paquet n'active pas pour vous. Si
   NetworkManager/ifupdown gère déjà votre interface réseau principale, dites-lui de laisser
   `nspawnbr0` tranquille (par ex. le `unmanaged-devices=interface-name:nspawnbr0` de
   NetworkManager.conf) pour que networkd reste libre de le gérer.
2. À l'intérieur du **modèle** de conteneur, avant la fabrication (la même étape que la
   fabrication d'`openssh-server` au [§2](#modèles-de-conteneurs-systèmes-de-fichiers-racine-de-base)) :
   `systemctl enable systemd-networkd` pour que `host0` récupère effectivement sa configuration
   DHCP depuis le pont — la sortie de `debootstrap` ne l'active pas par défaut. **Requis**, pas
   optionnel : sauter cela et les conteneurs de ce modèle ne quittent jamais BOOTING.
3. Démarrez (ou redémarrez) un conteneur — `machinectl start` asservit son veth dans `nspawnbr0`,
   il obtient une adresse et une route via DHCP depuis le pont, et nspawnmgr/`guacd` peuvent
   désormais l'atteindre directement.

### Résolution des conteneurs par nom

Les conteneurs gérés peuvent déjà se joindre les uns les autres par IP (rien dans la propre
configuration de pare-feu de nspawnmgr ne bloque le trafic `FORWARD` conteneur-à-conteneur — la
règle DROP de la chaîne `NSPAWNMGR-OUTBOUND` ne correspond qu'aux paquets sortants *propres* d'un
conteneur, quelle que soit la destination). Ce qui manque sans cette section est un moyen de
retrouver un pair par nom au lieu de son adresse interne, qui est assignée par DHCP par conteneur
et peut changer à travers les redémarrages.

`dnsmasq` est une vraie dépendance `apt` de ce paquet (contrairement à guacd/Tomcat, qui sont
empaquetés — voir [§2](#2-prérequis-de-lhôte) ; le comportement de service de fichier hosts de
`dnsmasq` est suffisamment simple et stable à travers les versions pour qu'il n'y ait pas besoin
d'en figer une). Installé et configuré automatiquement : lié uniquement à `nspawnbr0` (jamais
joignable depuis la propre interface LAN/montante de l'hôte — ce n'est pas, et ne doit jamais
devenir, un résolveur ouvert), servant ce qui se trouve dans `/etc/nspawnmgr/dns-hosts`. Chaque
conteneur obtient aussi automatiquement la propre adresse de `nspawnbr0` (`10.100.0.1`) comme son
serveur DNS, directement depuis le propre fichier `.network` de `nspawnbr0` — aucune étape admin
supplémentaire nécessaire. nspawnmgr régénère `/etc/nspawnmgr/dns-hosts` (`ContainerDnsSyncService`,
toutes les ~15s) à partir du propre nom et de la propre adresse interne de chaque conteneur MANAGED
actuellement `RUNNING` — la même adresse que `guacd`/le sondage de disponibilité résolvent déjà
(voir ci-dessus), donc rien de nouveau n'a besoin d'être découvert. dnsmasq ne remarque pas de
lui-même un fichier `addn-hosts` changé (aucun rechargement automatique/basé sur inotify pour cela,
seulement SIGHUP ou un redémarrage), donc chaque écriture est suivie d'un rechargement
(`nspawnmgr-reload-dnsmasq.sh`/`DnsReloader`) — sans cela, les conteneurs continueraient à échouer
à se résoudre les uns les autres peu importe à quel point le fichier sur disque est réellement à
jour.

Comme cette instance `dnsmasq` s'exécute directement sur l'hôte, elle lit et sert aussi par défaut
le propre `/etc/hosts` de l'hôte aux conteneurs (confirmé comme le comportement voulu en direct) —
les propres entrées LAN statiques d'un admin là-bas (par ex. `192.168.1.15 acer`) deviennent
résolubles depuis l'intérieur de chaque conteneur aussi, pas seulement depuis l'hôte lui-même.
La seule mise en garde : si `/etc/hosts` fait aussi correspondre le nom d'hôte nu de l'hôte à une
adresse de bouclage (la propre convention `127.0.1.1 <nom-hôte>` de Debian) *et* que ce même nom nu
est défini comme le paramètre de nom d'hôte externe ci-dessous, les deux sources entrent en
collision et dnsmasq peut répondre avec l'une ou l'autre adresse — évitez de choisir un nom court
déjà mappé dans `/etc/hosts` pour ce paramètre.

`/etc/nspawnmgr/dns-hosts` porte aussi une entrée fixe de plus : le propre nom d'hôte externe de
l'hôte (`nspawnmgr.host.external-hostname`/`HOST_EXTERNAL_HOSTNAME` — détecté automatiquement au
moment de l'installation par `setup-sudo-account.sh`, modifiable en direct par la suite sur
[`/admin/settings`](#paramètres-modifiables-en-direct-adminsettings)), pointant vers la propre
adresse fixe de `nspawnbr0` (`10.100.0.1`). Un conteneur n'a absolument aucune autre route de
retour vers l'hôte — c'est ce qui permet de résoudre le propre nom de l'hôte pour atteindre tout ce
que l'hôte redirige en retour (par ex. un [mappage de port
personnalisé](#mappages-de-ports-personnalisés-et-accès-sortant)). Maintenu synchronisé de la même
façon et selon le même calendrier que les entrées de conteneur ci-dessus ; entièrement omis tant
qu'il est encore à sa valeur par défaut non configurée `localhost` (faire correspondre
« localhost » lui-même à `10.100.0.1` serait activement faux, pas juste inutile).

Cette même instance `dnsmasq` est aussi le *seul* serveur DNS de chaque conteneur — pas seulement
pour les noms `.internal` — donc elle transfère aussi tout ce qui est en dehors de `.internal` vers
les résolveurs en amont configurés, `nspawnmgr.dns.upstream-servers` (par défaut
`1.1.1.1,9.9.9.9`), modifiable en direct sur
[`/admin/settings`](#paramètres-modifiables-en-direct-adminsettings) — par ex. pour pointer les
conteneurs vers un serveur DNS d'entreprise à la place. Sans amont configuré, le propre
`dnf`/`pacman`/`apt` d'un conteneur (récupérant depuis leurs vrais miroirs de paquets) ou tout
autre chose ayant besoin d'un vrai nom d'hôte internet échoue purement et simplement avec « Could
not resolve host » — confirmé en direct. Toujours pas un résolveur ouvert au sens ci-dessus : le
transfert se produit sur la propre route internet normale de l'hôte, et `dnsmasq` lui-même reste
lié uniquement à `nspawnbr0`, injoignable depuis l'extérieur du pont de conteneurs.

Les serveurs en amont vivent dans leur propre fichier, `/etc/dnsmasq.d/nspawnmgr-upstream.conf` —
séparé du `nspawnmgr.conf` principal ci-dessus — auto-inclus aux côtés de celui-ci par le propre
`conf-dir=/etc/dnsmasq.d/` de dnsmasq (le `/etc/dnsmasq.conf` par défaut de Debian), aucune
directive supplémentaire nécessaire. `ContainerDnsSyncService` le maintient synchronisé avec le
paramètre actuel de la même façon qu'il maintient `dns-hosts` synchronisé avec les conteneurs en
cours d'exécution (interrogé toutes les ~15s, réécrit uniquement quand la valeur effective change
réellement). `postinst` l'amorce avec le même défaut `1.1.1.1`/`9.9.9.9` à la première installation
(uniquement si le fichier n'existe pas déjà), pour que la résolution en amont fonctionne dès le
tout premier démarrage, avant même que nspawnmgr lui-même ne soit prêt à en prendre le relais.

Les conteneurs se résolvent les uns les autres par leur nom nspawnmgr nu (`b1`) ou par un FQDN sous
le suffixe fixe `.internal` (`b1.internal`) — les options `domain=`/`expand-hosts` de dnsmasq
servent les deux formes depuis les mêmes entrées `dns-hosts` automatiquement, aucune configuration
séparée. `internal` est le TLD à usage spécial de l'IANA réservé exactement pour cela (RFC 8375, la
même catégorie que `home.arpa`), pas un domaine inventé, donc il est garanti de ne jamais entrer en
collision avec un vrai domaine public. La portée est limitée aux conteneurs MANAGED uniquement
(EXTERNAL, les hôtes configurés par l'admin ont déjà leur propre `hostname` et ne sont pas ajoutés
ici), et l'espace de noms est plat à travers tous — c'est purement une joignabilité au niveau
réseau, indépendante des conteneurs qu'un utilisateur donné peut voir ou auxquels il peut se
connecter dans l'interface web (la grille Machines ne montre que les machines qu'un utilisateur
possède ou avec lesquelles il a été partagé, sauf pour un admin, qui voit tout indépendamment de la
propriété).

Deux éléments supplémentaires sont nécessaires pour que cela fonctionne de bout en bout :

- **Côté conteneur** : `systemd-resolved` refuse d'envoyer un nom non qualifié (sans point) comme
  `b2` à un vrai serveur DNS du tout — seulement à LLMNR/mDNS — à moins que le lien n'ait un
  domaine de routage/recherche configuré pour le qualifier. Le DHCP pourrait fournir cela, mais
  cela exige que le propre `80-container-host0.network` du conteneur (généré par `systemd-nspawn`
  lui-même, pas quelque chose que ce modèle contrôle) adhère avec `UseDomains=yes`, ce qu'il ne
  fait pas par défaut. Le modèle expédie plutôt un extrait statique à
  `/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf`
  (`[Network]\nDomains=internal`), fusionné par nom de fichier de la même façon qu'un extrait
  d'unité systemd l'est — contourne entièrement le DHCP et ne dépend d'aucune option réellement
  envoyée.
- **Côté dnsmasq** : `domain=`/`expand-hosts` seuls ne contrôlent que le suffixe dont dnsmasq
  *décore ses propres réponses* — ils ne le rendent pas autoritaire pour une requête qui *arrive*
  déjà pré-qualifiée (exactement ce qu'un conteneur avec le domaine de routage ci-dessus envoie
  maintenant). Sans aussi définir `local=/internal/`, une requête `b2.internal` entrante passe
  entièrement à travers la correspondance hosts/`addn-hosts` et est transférée en amont comme tout
  autre nom — `.internal` n'existe pas publiquement, donc cela échoue simplement (et sinon
  divulguerait les noms de conteneurs à quel que soit le résolveur public configuré).
  `local=/internal/` marque `.internal` comme la propre zone autoritaire de dnsmasq : répondre
  uniquement depuis ses propres données hosts, `NXDOMAIN` pour tout ce qui est genuinement inconnu
  là-bas, ne jamais transférer.

Si vous éditez jamais l'un ou l'autre fichier dnsmasq directement à la main sur un hôte en cours
d'exécution : `domain=`, `expand-hosts`, `local=` (dans `nspawnmgr.conf`), et `server=` (dans
`nspawnmgr-upstream.conf`) sont tous structurels — dnsmasq ne les analyse qu'au démarrage du
processus, confirmé en direct — contrairement à `addn-hosts`, que
`DnsReloader.reload()`/`nspawnmgr-reload-dnsmasq.sh` recharge à chaud correctement via `SIGHUP`. Un
simple `systemctl reload dnsmasq` après avoir édité à la main l'un des structurels n'a aucun effet
; utilisez `systemctl restart dnsmasq`. `ContainerDnsSyncService` connaît déjà cette distinction :
un changement d'`addn-hosts` passe par `DnsReloader.reload()` (SIGHUP) comme ci-dessus, mais un
changement de serveurs-en-amont passe par le `DnsReloader.restart()`/
`nspawnmgr-restart-dnsmasq.sh` séparé (un `systemctl restart` complet) à la place — utiliser
`reload()` pour celui-là laisserait le fichier sur disque correct pendant que dnsmasq continuerait
silencieusement à répondre avec ce avec quoi il a réellement démarré en dernier. Une installation/
mise à niveau de paquet normale n'a besoin ni de l'un ni de l'autre : le postinst du `.deb` émet
toujours son propre `restart` complet quand il (ré)installe `nspawnmgr.conf`.

### Découvrir des machines créées en dehors de nspawnmgr

Si une machine a été créée à la main directement sur l'hôte — `machinectl clone`/`debootstrap`/
`import-tar` exécutés vous-même, ou une image restaurée depuis une sauvegarde — nspawnmgr n'a
aucune idée qu'elle existe jusqu'à ce qu'un admin clique sur **Discover machines** sur la liste de
conteneurs. Cela compare chaque nom d'image que `machinectl` connaît actuellement contre la propre
base de données de nspawnmgr et enregistre tout ce qui n'est pas déjà suivi comme un conteneur
MANAGED ordinaire, **appartenant à quel que soit l'admin qui a lancé la découverte**. Le relancer
est sûr — tout ce qui est déjà suivi (par nom) est sauté.

La découverte enregistre l'existence de la machine et vous permet de la démarrer/arrêter/supprimer
et de la voir résolue par nom (voir ci-dessus). Elle n'installe délibérément jamais de compte admin
SSH/RDP/VNC de la façon dont créer un conteneur à travers nspawnmgr le fait — contrairement à un
conteneur que nspawnmgr a provisionné lui-même, il n'y a aucun moyen de savoir ce qui existe déjà à
l'intérieur d'une image construite à la main, donc elle ne suppose jamais un nom de compte ni
n'exécute `useradd`/n'installe un serveur pour l'un des trois. Ce qu'elle fait *effectivement* :
juste après avoir enregistré chaque machine, elle vérifie si SSH (port 22), RDP (port 3389), ou VNC
(port 5900) écoute déjà, et si oui, câble automatiquement une connexion Guacamole pour elle — en
mode **identifiants-à-la-demande**, le même mécanisme que la page Hosts ci-dessous utilise, donc on
vous demande un nom d'utilisateur/mot de passe à chaque connexion plutôt que nspawnmgr en générant
et en stockant un. Si aucun de ces ports n'était encore ouvert au moment de la découverte (ou que
vous en activez un sur la machine par la suite), faites-le manuellement depuis la propre page de
détail du conteneur à la place — voir « Accès distant » ci-dessous.

### Accès distant pour les conteneurs que nspawnmgr n'a pas configurés lui-même

La page de détail d'un conteneur a une section **Remote access** pour chacun de SSH, RDP, et VNC
chaque fois que nspawnmgr n'a aucun identifiant généré pour ce protocole dessus — toujours vrai
pour un conteneur découvert, et aussi vrai pour un conteneur créé par nspawnmgr ordinaire si
RDP/VNC a été décliné lors de sa création. Cliquer sur **Enable SSH/RDP/VNC access** vérifie que le
port écoute effectivement en ce moment et, seulement si c'est le cas, câble une connexion Guacamole
par identifiants-à-la-demande exactement comme la propre étape de câblage automatique de la
découverte ci-dessus ; **Disable** la retire à nouveau. Cette vérification se produit une fois, au
moment où vous cliquez sur Enable — si le service à l'intérieur du conteneur s'arrête à nouveau par
la suite, le bouton Connect reste activé jusqu'à la prochaine tentative de connexion échouée,
plutôt que nspawnmgr ne re-sonde continuellement chaque conteneur en arrière-plan.

Cette section n'est intentionnellement jamais proposée pour un protocole que nspawnmgr gère déjà
avec un vrai identifiant généré (le SSH de chaque conteneur, et RDP quand demandé à la création) —
cette connexion est laissée complètement tranquille, pour que cette fonctionnalité ne puisse jamais
silencieusement remplacer des identifiants générés fonctionnels par une connexion par
identifiants-à-la-demande.

### Hôtes : machines externes gérées par l'administrateur

Un **Host** est une entrée pour une machine arbitraire sur le réseau qui n'est pas du tout un
conteneur géré par nspawnmgr — une machine Windows existante, un NAS, le serveur d'une autre
équipe, tout ce qui est joignable par SSH/RDP/VNC et qu'il est pratique d'accéder à travers le même
flux SSO Guacamole que tout le reste ici. Il n'y a pas de page Hosts séparée : un Host est une
ligne `Container` sous le capot (genre `EXTERNAL`), donc il apparaît comme une carte ordinaire — un
badge `HOST` fixe au lieu d'un badge de backend — juste aux côtés des machines nspawn/podman/QEMU
sur la grille principale **Machines**, et sa page de détail est la même route `/containers/{id}`
que toute autre machine utilise. Les admins en ajoutent un depuis l'élément **New Host** du menu
« + » (`/admin/hosts/new`, admin uniquement) : un nom, un nom d'hôte/IP, un nom d'utilisateur
propriétaire (doit appartenir à un utilisateur déjà connecté au moins une fois), et lequel de
SSH/RDP/VNC proposer plus le port pour chacun. Un admin visualisant la propre page de détail de cet
hôte obtient des boutons **Edit host** (retour au même formulaire, sur
`/admin/hosts/{id}/edit`) et **Delete host** dans son panneau Manage — il n'y a pas de page de
liste de hosts séparée ; la base de données est l'unique source de vérité.

**La visibilité suit la même règle propriétaire/admin/partagé que toute autre machine** — un Host
n'est pas public juste parce qu'il est créé par un admin ; seul un admin, son propriétaire, ou
quelqu'un avec qui il a été explicitement partagé le voit dans sa propre grille Machines
(`ContainerRepository.findVisibleToUserOrderByName` applique cela uniformément à travers les
lignes nspawn, podman, QEMU, et Host).

**RUNNING/STOPPED est résolu en direct, pas stocké.** Comme nspawnmgr ne contrôle pas du tout le
cycle de vie d'un Host, son badge d'état provient d'une simple vérification de joignabilité TCP
(`HostLivenessService`) contre lequel de ses ports SSH/RDP/VNC configurés est activé — SSH d'abord
s'il est présent, puis RDP, puis VNC — mise en cache pendant une minute par host pour que la
grille Machines et la propre page de détail du host ne déclenchent pas chacune un sondage frais à
chaque requête. Un Host sans aucun des trois activés n'a rien à sonder et affiche toujours RUNNING.

Les connexions demandent toujours des identifiants en direct — nspawnmgr ne stocke jamais de mot de
passe pour un host, le même mécanisme par identifiants-à-la-demande que le propre câblage
automatique de la découverte et la section Remote access par conteneur ci-dessus utilisent tous
deux.

Le champ nom d'hôte/IP peut être un vrai nom d'hôte, pas seulement une adresse — sur une
installation auto-hébergée, le propre client SSH/RDP/VNC de Guacamole s'exécute à l'intérieur du
conteneur auto-hébergé `nspawnmgr`, dont le seul chemin DNS est le propre dnsmasq de nspawnmgr
(noms de conteneurs plus résolveurs publics en amont), sans visibilité sur la propre résolution de
noms d'un LAN privé. Pour contourner cela, nspawnmgr résout à nouveau lui-même le nom d'hôte sur
l'hôte sous-jacent (via le même compte SSH doté des droits sudo utilisé pour toute autre opération
privilégiée) à chaque fois que quelqu'un se connecte, et transmet à Guacamole l'adresse résolue
directement au lieu du nom d'hôte — donc un nom uniquement LAN que seuls le propre DNS/NetBIOS/mDNS
de votre réseau connaissent fonctionne toujours, et une adresse réassignée par DHCP est captée
automatiquement à la prochaine connexion sans qu'un admin n'ait besoin de le remarquer et de
réenregistrer l'entrée. Si le nom d'hôte ne se résout pas sur l'hôte au moment de la connexion, la
tentative de connexion échoue avec une erreur claire plutôt que de continuer avec une adresse
périmée.

Le partage fonctionne de la même façon que pour les conteneurs : le propriétaire gère qui d'autre
peut se connecter depuis la propre page de détail de l'entrée. Un admin qui n'est pas le
propriétaire voit à la place un bouton **Take ownership** sous Manage là-bas — utile pour reprendre
un Host (ou toute machine) dont le propriétaire est depuis parti, sans avoir besoin d'accès à la
base de données.

Les boutons SSH/RDP/VNC à la fois sur la grille Machines et sur la propre page de détail d'un Host
ouvrent la session Guacamole dans un nouvel onglet de navigateur plutôt que de quitter la page —
utile en se connectant à plusieurs machines depuis la même page. Ouvrir l'un depuis la carte d'un
Host utilise `/hosts/{nom}/session/{protocole}`, son propre espace de noms d'URL distinct du
`/containers/{nom}/session/{protocole}` d'une machine ordinaire — un Host est une ligne Container
sous le capot comme noté ci-dessus, mais l'URL de *session* qu'un utilisateur voit réellement dans
son navigateur ne dit délibérément pas « containers » pour quelque chose qui n'en est pas un du
point de vue d'un admin. Les deux routes rendent le template/JS identique en dessous (un iframe
plus une requête vers le même point de terminaison API
`/api/containers/{id}/session/{protocole}`) ; seule l'URL de la page diffère. Les deux se basent
sur le **nom** de la machine, pas son identifiant numérique — un choix délibéré pour que l'URL dans
un lien partagé ou dans l'historique d'un navigateur reste significative.

### Mappages de ports personnalisés et accès sortant

Au-delà de SSH/RDP ci-dessus, le **propriétaire** d'un conteneur peut gérer lui-même deux choses de
plus depuis sa page de détail — aucune action admin nécessaire pour ni l'une ni l'autre :

- **Mappages de ports entrants personnalisés** : toute redirection TCP ou UDP supplémentaire
  port-hôte → port-conteneur, le propriétaire choisissant les deux numéros de port exactement.
  nspawnmgr vérifie que le port hôte demandé n'est pas déjà lié par un autre mappage personnalisé
  avant de l'accepter. Un mappage est écrit dans le fichier `.nspawn` immédiatement mais ne prend
  effet que la prochaine fois que le conteneur est (re)démarré — en ajouter un à un conteneur en
  cours d'exécution affiche un avis « redémarrage requis » plutôt que de le redémarrer
  automatiquement.
- **Bascule d'accès internet sortant** : contrairement à la configuration de masquerade tout-ou-
  rien à l'échelle de l'hôte ci-dessus, chaque conteneur peut individuellement avoir son accès
  sortant bloqué. nspawnmgr gère cela lui-même avec une chaîne iptables dédiée
  `NSPAWNMGR-OUTBOUND` (créée automatiquement la première fois qu'elle est nécessaire, atteinte
  depuis le haut de `FORWARD`) contenant une règle `DROP` par conteneur avec accès sortant
  désactivé, indexée sur la véritable interface veth côté hôte de ce conteneur — que nspawnmgr
  recherche dynamiquement à chaque fois (via l'ifindex du pair du veth), puisque, comme ci-dessus,
  le nom du veth n'est pas une chaîne prévisible dérivée du nom du conteneur. Basculer ceci prend
  effet immédiatement, sans redémarrage nécessaire, pour un conteneur en cours d'exécution.
- **Liste blanche sortante** : pendant que l'accès sortant est désactivé, le propriétaire peut
  quand même percer vers des destinations spécifiques — une adresse IPv4 littérale, un port, et un
  protocole (TCP/UDP) — par ex. `127.0.0.1` pour que le conteneur puisse atteindre un autre
  conteneur/service co-localisé sans lui accorder un accès internet général. Implémenté comme des
  règles ACCEPT avant la règle DROP du conteneur dans la même chaîne `NSPAWNMGR-OUTBOUND` ; chaque
  changement vide et reconstruit les règles de ce conteneur depuis zéro plutôt que de les corriger
  en place. N'a aucun effet pendant que l'accès sortant est activé — tout est déjà joignable dans
  ce cas. Prend aussi effet immédiatement, aucun redémarrage nécessaire.

Les deux exigent que la commande `iptables` soit disponible et utilisable sans mot de passe via le
compte doté des droits sudo du [§3](#3-le-compte-ssh-doté-des-droits-sudo) — le même compte et
mécanisme que nspawnmgr utilise déjà pour écrire les fichiers `.nspawn` et démarrer/arrêter les
conteneurs.

## 3. Le compte SSH doté des droits sudo

Créez un compte local dédié sur le même hôte, avec un accès sudo limité, dans lequel nspawnmgr se
connectera en SSH (toujours par bouclage, `127.0.0.1`) pour réellement exécuter
`machinectl`/`systemd-run` et toucher des chemins appartenant à root. **Recommandé :** laissez
`packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh` faire cela pour vous — c'est le même
script que le `postinst` du `.deb` exécute, mais il est entièrement exécutable de façon autonome,
sans du tout construire ni installer le paquet :

```bash
sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
```

Exécuté depuis un checkout de ce dépôt (aucun drapeau nécessaire — il auto-détecte les
`privileged-scripts/` et `debian/nspawnmgr.sudoers` frères juste à côté de lui-même), il crée le
compte système `nspawnmgr_exec`, génère et stocke un mot de passe aléatoire pour lui, génère une
paire de clés SSH, installe les scripts wrapper référencés ci-dessous dans
`/usr/lib/nspawnmgr/privileged/`, installe et valide l'octroi sudoers, et ajoute une dérogation
sshd `PasswordAuthentication` pour le compte si votre hôte le désactive globalement. Il est
idempotent — sûr à relancer après une mise à niveau ou pour récupérer des scripts wrapper mis à
jour. Voir le propre commentaire d'en-tête du script pour le détail complet.

Si vous préférez configurer cela entièrement à la main à la place (par ex. pour utiliser un nom de
compte différent), voyez ce que le script lui-même fait comme référence — mais notez les deux
niveaux de privilège ci-dessous, puisqu'un `usermod -aG sudo` général (n'importe quelle commande,
toujours via un mot de passe) ne correspond plus à la façon dont nspawnmgr appelle réellement ce
compte.

### Deux niveaux de privilège

L'accès sudoers pour ce compte est divisé en deux niveaux, pas un seul :

- **NOPASSWD** — les commandes de forme fixe, toujours sûres : `machinectl start/poweroff/
  terminate/reboot/remove/show`, `systemd-run --machine=... --pipe --quiet --wait /bin/sh -s`
  (exécuter un script de conteneur stocké — voir « Frontière de confiance : scripts de conteneur »
  ci-dessous pour savoir pourquoi cette forme spécifique de `systemd-run` est NOPASSWD alors que la
  générale ci-dessous ne l'est pas), et les scripts wrapper sous
  `/usr/lib/nspawnmgr/privileged/` qui gèrent l'écriture des paramètres `.nspawn`, la suppression
  des fichiers d'un conteneur, et la synchronisation du pare-feu sortant. Ce sont des actions
  routinières déclenchées par le propriétaire (démarrer un conteneur, éditer ses mappages de ports,
  le supprimer, exécuter un script qu'il a écrit) qui ne doivent jamais bloquer en attendant un
  admin, quel que soit le mode de création de conteneur actif ci-dessous.
- **Mot de passe requis** (aucun tag `NOPASSWD`) — `systemd-run --machine=... --pipe --quiet
  --wait` (exécute du contenu arbitraire défini par le modèle en tant que root à l'intérieur d'un
  nouveau conteneur — voir « Frontière de confiance » ci-dessous), le wrapper
  `nspawnmgr-clone-template.sh`, et le wrapper `nspawnmgr-create-debian-template.sh` (télécharge/
  extrait un vrai rootfs Debian — voir « Modèles de conteneurs » du §2, le bouton « Set up
  debian-minimal » de la page admin Templates). Les trois ne sont qu'au moment de la création — les
  deux premiers appelés exactement une fois par conteneur depuis `ProvisioningService`, le
  troisième uniquement à la demande depuis un admin quand aucun modèle n'existe encore. Quel mot de
  passe est utilisé — et si l'un d'eux est même disponible sans l'implication d'un admin — dépend du
  mode ci-dessous.

Chaque commande privilégiée passe par l'une de ces deux invocations wrapper-script à arguments
fixes ou `machinectl`/`systemd-run` — nspawnmgr ne demande jamais à sudo d'exécuter un script en
ligne arbitraire, précisément pour que l'octroi sudoers ci-dessus puisse correspondre à une
commande/un chemin exact plutôt que d'avoir à faire correspondre du texte de script par joker (ce
qui serait fragile : tout futur changement au contenu du script invaliderait silencieusement — ou
élargirait silencieusement trop — l'octroi).

### Mode de création de conteneur : secret stocké ou approbation de l'administrateur

Que la création d'un conteneur soit entièrement en libre-service ou nécessite l'aval d'un admin est
**dérivé** du fait que `nspawnmgr.ssh.password`/`SSH_PASSWORD` soit configuré — il n'y a pas de
bascule séparée :

- **Mode secret stocké / libre-service** (mot de passe configuré, le défaut du `.deb`) : la requête
  « créer un conteneur » d'un propriétaire provisionne immédiatement et automatiquement, comme
  avant que cette fonctionnalité n'existe.
- **Mode approbation admin** (mot de passe laissé vide) : un nouveau conteneur atterrit dans un
  état `PENDING_APPROVAL` au lieu de provisionner immédiatement. La page **Requests**
  (`/requests` — son élément de navigation latérale n'apparaît, pour quiconque, que tant que ce
  mode est actif) le liste aux côtés de toute requête de compte utilisateur dans-le-conteneur en
  attente dans une vue combinée. Un admin voit et peut agir sur chaque élément en attente de chaque
  utilisateur ; un non-admin ne voit que les siens et peut les **Deny** (passe à un état terminal
  `DENIED`, aucune tentative SSH jamais faite) mais pas les **Approve** — approuver nécessite un
  mot de passe sudo, fourni en ligne, utilisé uniquement pour les étapes de création de cet élément
  précis, gardé en mémoire et remis à zéro une fois cette exécution terminée, jamais persisté —
  délibérément jamais demandé qu'à un admin.

La connexion de transport SSH et le mot de passe sudo partagent la même valeur configurée, donc
vider `SSH_PASSWORD` pour sélectionner le mode approbation admin laisserait sinon la session SSH
elle-même sans rien pour s'authentifier — même pour le niveau NOPASSWD ci-dessus. **Le mode
approbation admin exige donc que `nspawnmgr.ssh.private-key-path`/`SSH_PRIVATE_KEY_PATH` soit
défini**, pour que l'authentification de transport SSH utilise une clé au lieu du mot de passe
(désormais vide). `setup-sudo-account.sh` génère cette clé inconditionnellement quel que soit le
mode, donc changer de mode plus tard n'est vraiment que vider/définir une variable d'environnement
et redémarrer — rien d'autre à configurer. nspawnmgr échoue à démarrer si ni un mot de passe ni une
clé privée n'est configuré du tout (`SshPropertiesValidator`), plutôt que de faire apparaître cela
comme un échec de connexion déroutant lors de la première action sur un conteneur.

### Rôles administrateur et utilisateur

Le rôle d'un utilisateur (`USER`/`ADMIN`) est nécessaire pour contrôler l'accès à la page
d'approbation ci-dessus. Deux modes, encore une fois sélectionnés par le fait qu'une valeur de
configuration soit définie — cette fois `nspawnmgr.auth.user-is-admin-json` :

- **Géré par l'application** (par défaut, vide) : le **tout premier utilisateur à se connecter**
  est automatiquement promu `ADMIN` ; tous les autres ont `USER` par défaut. À partir de là, tout
  admin peut promouvoir ou rétrograder tout autre utilisateur sur `/admin/users`. Les rôles sont
  persistants — jamais recalculés silencieusement à la connexion.
- **Géré en externe** (`nspawnmgr.auth.user-is-admin-json` défini sur un JsonPath dans le même JSON
  d'identité que `auth.war` renvoie déjà, aux côtés de `user-id-json`/`user-username-json` etc.) :
  le rôle est recalculé à neuf depuis ce JSON à chaque connexion à la place — promotion et
  rétrogradation toutes deux — et la page manuelle d'octroi/révocation rejette entièrement les
  changements, puisque la source d'identité externe est autoritaire dans ce mode.

### Frontière de confiance : commandes de provisionnement définies par le modèle

Le niveau mot-de-passe-requis ci-dessus permet à `systemd-run` d'exécuter du contenu en tant que
root à l'intérieur d'un conteneur. Ce contenu provient toujours de l'un de : une chaîne littérale
dans `ProvisioningService` lui-même, ou `Template.installSshCommand`/`installXrdpCommand`. Les
modèles sont modifiables à travers `/admin/templates`, contrôlés par le rôle ADMIN existant sur
`/admin/**`, pas un flux d'approbation séparé. En d'autres termes : **quiconque détient le rôle
ADMIN contrôle effectivement ce qui s'exécute en tant que root à l'intérieur de chaque conteneur
créé à partir d'un modèle qu'il édite.** En mode rôle géré par l'application, tout admin actuel
peut accorder ADMIN à n'importe qui d'autre sur `/admin/users`, en libre-service, sans étape
d'approbation supplémentaire. Les utilisateurs connectés ordinaires (non-admin) ne peuvent
toujours pas du tout atteindre cela — seul `GET /api/templates` (modèles actifs, résumé
uniquement) est exposé en dehors de `/admin/**`.

### Frontière de confiance : scripts de conteneur

Le propriétaire d'un conteneur (ou quiconque avec qui ce conteneur a été partagé — voir « Shared
with » sur la page de détail du conteneur) peut définir des scripts nommés et les exécuter en tant
que root à l'intérieur de ce même conteneur, via `/containers/{id}/scripts`. C'est une forme de
confiance différente de l'édition de modèle ci-dessus : l'auteur est le propre propriétaire/
utilisateur-partagé du conteneur, et le script ne s'exécute jamais qu'à l'intérieur de **ce seul
conteneur**, jamais celui de quelqu'un d'autre. Ces utilisateurs ont déjà un accès complet et
interactif à un shell root sur ce conteneur exact à travers leur propre session SSH Guacamole —
exécuter un script enregistré à travers cette fonctionnalité n'accorde aucun privilège qu'ils
n'avaient pas déjà ; c'est purement une commodité (nommé, réutilisable, un clic au lieu de le
retaper par SSH à chaque fois). C'est pourquoi exécuter un script est NOPASSWD
(`/usr/bin/systemd-run --machine=* --pipe --quiet --wait /bin/sh -s`, forme fixe, uniquement cette
commande exacte) contrairement au contenu défini par un modèle ci-dessus, qui s'exécute à
l'intérieur des conteneurs *d'autres* personnes et est écrit par un admin, pas le propre
propriétaire du conteneur.

**« Shared with » accorde plus que l'accès à la session.** Partager un conteneur accorde à l'autre
utilisateur une session SSH/RDP Guacamole *et* la capacité de créer, éditer, supprimer, et exécuter
les scripts de ce conteneur (accès root complet, effectivement — voir ci-dessus) ; il n'y a pas de
bascule séparée pour accorder l'un sans l'autre. Si vous avez partagé des conteneurs avec des
personnes purement pour la commodité du bureau à distance, elles ont aussi les droits sur les
scripts.

### Autres notes de configuration

- Ce compte a aussi besoin d'un accès en lecture/écriture à l'endroit où vous pointez
  `TEMPLATES_DIR`.
- Comme c'est bouclage-uniquement par conception, nspawnmgr utilise par défaut
  `strict-host-key-checking: false` pour cette connexion. N'activez cela que si vous pointez un
  jour cela vers un hôte non-localhost, et assurez-vous que le compte Tomcat a un
  `~/.ssh/known_hosts` peuplé pour la cible d'abord.
- **Tout ceci suppose que nspawnmgr gère les conteneurs sur le même hôte sur lequel il s'exécute**
  (le seul arrangement supporté par le `.deb`). Pointer `nspawnmgr.ssh.host` vers un hôte différent
  à la place est un scénario configuré manuellement, non supporté par l'outillage : vous devriez
  répéter indépendamment la configuration de compte/sudoers/paire-de-clés de cette section
  vous-même sur cet hôte distant.
- **L'accès SSH de `nspawnmgr_exec` est bouclage-uniquement par conception** — ne remettez ses
  identifiants à rien en dehors de cet hôte. Si vous voulez qu'un pipeline CI/CD externe puisse
  installer/mettre à jour des modèles de conteneurs, utilisez plutôt le compte séparé et
  délibérément plus étroit `nspawnmgr_ci` (voir « Installer/mettre à jour des modèles depuis un
  pipeline CI/CD » ci-dessus) — il est isolé dans son propre fichier sudoers avec exactement un
  octroi de forme fixe, contrairement à l'accès large NOPASSWD/MOT-DE-PASSE de `nspawnmgr_exec`, et
  est destiné à être atteint sur le réseau.

Vous brancherez le nom d'utilisateur/mot de passe (ou la clé privée) de ce compte dans la propre
configuration de nspawnmgr comme `nspawnmgr.ssh.*` (ou
`SSH_USERNAME`/`SSH_PASSWORD`/`SSH_PRIVATE_KEY_PATH`) au [§9](#9-configurer-nspawnmgr).

## 4. Base de données

MySQL, MariaDB, ou PostgreSQL — aucune option H2. H2 est utilisé en interne uniquement par le
harnais de test dev-stack/CI (une base de données en mémoire, disparue au moment où cette JVM
s'arrête) ; ce n'a jamais été une cible de déploiement supportée et il ne reste aucun chemin de
code pouvant la sélectionner comme telle. MySQL et MariaDB partagent le même pilote JDBC, le même
schéma, et le même emplacement de migration Flyway — choisir l'un plutôt que l'autre ne change que
le nom de machine que l'assistant utilise par défaut (ci-dessous), pas quel chemin de code
s'exécute. `spring.datasource.url` et
`spring.flyway.locations: classpath:db/migration/<fournisseur>` doivent concorder (voir
`DB_VENDOR` dans la référence des variables d'environnement — toujours `mysql` ou `postgresql`,
jamais `mariadb`). Flyway exécute les migrations automatiquement au démarrage ;
`spring.jpa.hibernate.ddl-auto` vaut `validate`, jamais `update` — le schéma est entièrement sous
la responsabilité de Flyway.

La base de données est **auto-hébergée**, de la même façon que nspawnmgr lui-même l'est
([§1](#1-vue-densemble-de-larchitecture)) — l'assistant ci-dessous provisionne toujours un tout
nouveau conteneur Debian pour l'exécuter, plutôt que de vous demander de le pointer vers un
serveur existant.

### Assistant de configuration au premier démarrage

Vous n'avez pas besoin de préparer une base de données ni de définir
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_VENDOR` vous-même avant de démarrer Tomcat la première
fois — cet assistant le fait pour vous. Il vit dans son propre WAR (`ROOT.war`), déployé au
contexte racine de Tomcat à l'intérieur de la machine auto-hébergée `nspawnmgr`
(`http://<hôte>:<port redirigé>/`, [§1](#1-vue-densemble-de-larchitecture)) plutôt qu'à l'intérieur
de `nspawnmgr.war` lui-même : visiter `/` vous redirige directement vers `/nspawnmgr/` une fois
qu'une base de données fonctionnelle est configurée, ou montre cet assistant sinon. Atteindre
`/nspawnmgr/` directement pendant qu'aucune base de données n'est encore configurée vous redirige
simplement vers `/` — l'assistant est toujours l'unique endroit qui décide dans quel état vous êtes.

Choisissez un **moteur de base de données** (MySQL, MariaDB, ou PostgreSQL) et, optionnellement,
un **nom de machine de base de données** non par défaut — par défaut `mysqldb`, `mariadb`, ou
`postgresdb` selon le moteur, modifiable. Remplissez aussi un **nom d'utilisateur et mot de passe
nspawnmgr initiaux** — un vrai compte Linux, créé à l'intérieur de la propre machine auto-hébergée
`nspawnmgr`, avec lequel vous vous connecterez une fois la configuration terminée (voir
[§8](#8-auth-backend-de-connexion) pour savoir pourquoi c'est tout ce dont le backend PAM de
`auth.war` a besoin, sans configuration supplémentaire).

À la soumission, l'assistant :

1. Provisionne la machine de base de données (`nspawnmgr-bootstrap-db-machine.sh`, exécuté sur le
   même compte SSH doté des droits sudo que toute autre opération privilégiée dans cette
   application utilise, voir [§3](#3-le-compte-ssh-doté-des-droits-sudo)) — clone un modèle
   Debian, installe le moteur choisi (MySQL et MariaDB installent tous deux le propre
   `mariadb-server` de Debian ; il n'y a pas de paquet Oracle MySQL séparé sur Debian), et attend
   qu'une unité systemd de premier démarrage à l'intérieur de cette machine crée les bases de
   données et utilisateurs arbitraires `nspawnmgr`/`guacamole` avec des mots de passe fraîchement
   générés une fois que le moteur est genuinement en cours d'exécution (pas tenté hors ligne — les
   deux moteurs ont réellement besoin de s'exécuter brièvement pour exécuter `CREATE DATABASE`/
   `CREATE USER`).
2. Exécute les propres migrations Flyway de nspawnmgr, puis les scripts de schéma de Guacamole
   (chaque installation démarre toujours depuis une toute nouvelle base de données, donc il n'y a
   pas de vérification « un schéma existe-t-il déjà » à faire ici), et câble pour vous l'extension
   `guacamole-auth-jdbc` de Guacamole (copie le JAR de l'extension dans
   `GUACAMOLE_HOME/extensions/` et écrit les propriétés
   `<fournisseur>-hostname`/`-port`/`-database`/`-username`/`-password` dans
   `GUACAMOLE_HOME/guacamole.properties` — voir « GUACAMOLE_HOME et le backend d'authentification »
   du [§7](#7-guacamole) pour ce à quoi cela sert). Si cette dernière étape échoue pour une raison
   quelconque, ce n'est pas fatal — la propre base de données de nspawnmgr (la chose qui décide
   réellement si cet assistant continue d'apparaître) fonctionne déjà à ce stade, et l'échec est
   simplement affiché comme un avertissement vous disant de terminer cette étape unique à la main.
3. Crée le compte Linux nspawnmgr initial à l'intérieur de la machine auto-hébergée `nspawnmgr`,
   via le même compte doté des droits sudo se reconnectant dans cette machine — le même mécanisme
   que `ProvisioningService` utilise déjà pour créer le propre compte de connexion d'un conteneur
   géré ordinaire.
4. Enregistre les paramètres de connexion nspawnmgr fonctionnels dans
   `/etc/nspawnmgr/db-config/db.properties` à l'intérieur de la machine `nspawnmgr` (appartenant à
   `tomcat:tomcat`, créé automatiquement par `nspawnmgr-bootstrap-app-machine.sh`).

La page de succès recharge immédiatement en place à la fois le contexte de `nspawnmgr.war` et celui
propre de Guacamole — aucun bouton à cliquer, aucun redémarrage de Tomcat nécessaire — en touchant
`/opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml` et `guacamole.xml` (le même wrapper
`nspawnmgr-write-file.sh` que les autres opérations privilégiées utilisent, exécuté via le propre
assistant SSH sans Spring de l'assistant puisqu'il n'y a pas encore de contexte applicatif à ce
stade du démarrage) ; le propre thread d'auto-déploiement en arrière-plan de Tomcat remarque chaque
changement et redéploie ce contexte en place. Pour `/nspawnmgr`, cela relance sa vérification de
joignabilité au démarrage et démarre l'application réelle cette fois. Guacamole a besoin du même
traitement : à un démarrage frais, sa propre webapp démarre (et lit
`guacamole.properties`/charge les extensions, une fois, à ce moment-là) avant même qu'un admin
n'ait eu la chance de remplir cet assistant du tout — sans aussi le redéployer ici, Guacamole
continuerait de tourner sans extension d'authentification soutenue par base de données chargée et
rejetterait chaque connexion, y compris le compte `guacadmin` que la propre étape de schéma de cet
assistant vient de créer. La page interroge `/nspawnmgr/` et vous y emmène automatiquement une fois
qu'il est prêt — généralement quelques secondes, pas le redémarrage complet de Tomcat que cela
nécessitait auparavant.

L'assistant lui-même enregistre à la fois la machine `nspawnmgr` et sa machine de base de données
comme des conteneurs ordinaires et visibles dans la propre liste de conteneurs de nspawnmgr —
appartenant au compte créé à l'étape 3 ci-dessus, avec une description « Virtual machine
management »/« Database server » chacun — directement dans son propre travail de base de données
juste après les migrations, aucune connexion requise d'abord (voir [« Découvrir des machines créées
en dehors de nspawnmgr »](#découvrir-des-machines-créées-en-dehors-de-nspawnmgr) pour le même
mécanisme d'enregistrement sous-jacent, sinon déclenché à la main par un admin). Quand vous vous
connectez pour la première fois (via ce même compte), vous êtes simplement reconnecté à l'identité
admin que l'assistant a déjà créée ([§3](#rôles-administrateur-et-utilisateur)) — les deux machines
sont déjà là, attendant. Elles ne sont ni cachées ni traitées comme un cas spécial par la suite ;
vous pouvez vous connecter en SSH à l'une ou l'autre, les partager, les supprimer, comme tout autre
conteneur — bien que supprimer la machine `nspawnmgr` depuis laquelle vous êtes actuellement en
train de fonctionner ne soit, de toute évidence, pas une bonne idée.

**Le formulaire de l'assistant lui-même n'est pas authentifié et est joignable depuis n'importe
quel hôte.** Il n'y a pas encore de base de données, donc il n'y a pas de table utilisateurs, donc
il n'y a pas de système de connexion derrière lequel se placer — quiconque peut atteindre ce port
avant que la base de données ne soit configurée peut la configurer. Restreignez l'accès réseau à ce
port vous-même (règles de pare-feu, en le gardant hors d'une interface publique jusqu'à ce que le
§4 soit terminé) si cela compte pour votre déploiement.

## 5. Installer nspawnmgr

Deux chemins à partir d'ici — choisissez-en un. **L'option A (le `.deb`) fait le §3 et la majeure
partie du §6 pour vous** ; l'option B est le parcours entièrement manuel du §6 et suivants. (Des
paquets Arch Linux et Fedora/RHEL existent aussi, avec la même automatisation que l'option A — voir
[« Installation sur Arch Linux »](#installation-sur-arch-linux) et [« Installation sur Fedora/RHEL
(RPM) »](#installation-sur-fedorarhel-rpm) juste après.) Dans tous les cas, le §4 (base de
données), la configuration Guacamole `GUACAMOLE_HOME`/JDBC au §7, les valeurs de configuration au
§9, et la vérification au §10 restent votre propre responsabilité — aucun des trois paquets
n'automatise plus que le *compte sudo* et *le déploiement des WAR dans Tomcat*, ni le propre
backend de stockage de Guacamole ni les paramètres au niveau applicatif de nspawnmgr.

**Ce dont vous avez besoin pour *construire* chaque format de paquet n'est pas la même chose que ce
dont vous avez besoin pour *l'installer*** — cela vaut la peine de le savoir avant de choisir un
chemin, particulièrement si la machine sur laquelle vous construisez n'est pas celle sur laquelle
vous déployez :

| Format | Besoins de construction | Besoins d'installation | Constructible de façon croisée ? |
|---|---|---|---|
| `.deb` (`packaging/nspawnmgr-deb/`) | JDK 21 + Maven (le plugin `jdeb` est du Java pur) | `apt`, Debian/Ubuntu | **Oui** — construisez sur tout hôte avec un JDK, y compris Arch/Fedora/Windows/macOS |
| Arch (`packaging/nspawnmgr-arch/`) | JDK 21 + Maven, **plus `makepkg`/`base-devel`** | `pacman`, Arch Linux | **Non** — `makepkg` est un outillage natif Arch sans équivalent multiplateforme ; l'hôte de construction doit lui-même être Arch (ou l'image de conteneur `archlinux/devtools`) |
| RPM (`packaging/nspawnmgr-rpm/`) | JDK 21 + Maven, **plus `rpm-build`** | `dnf`, Fedora/RHEL | **Non** — malgré la réputation de `rpm-maven-plugin`, il fait genuinement appel à un vrai binaire `rpmbuild` ; confirmé en direct qu'il échoue purement et simplement sur un hôte de construction non-RPM (par ex. Windows) sans équivalent multiplateforme, même histoire que le `makepkg` d'Arch |

Si vous n'avez pas de machine Arch ou Fedora de rechange pour construire cela,
`packaging/ci/arch-runner/bootstrap-arch-runner.sh` et
`packaging/ci/fedora-runner/bootstrap-fedora-runner.sh` montrent une façon d'en obtenir une sans
double démarrage ni matériel nu : les deux fabriquent un vrai rootfs dans un simple conteneur
`systemd-nspawn` (pas une image Docker/Podman — nspawn s'est révélé le plus simple ici, puisqu'il
partage par défaut l'espace de noms réseau de l'hôte plutôt que d'avoir besoin de son propre pont
juste pour la CI). Les jobs `arch-package` et `rpm-package` de `.gitea/workflows/build.yml`
montrent les commandes de construction exactes qui s'exécutent une fois que chaque conteneur
existe (installer le JDK/Maven/l'outillage d'empaquetage natif, puis
`BUILD_ARCH_PKG=1`/`BUILD_RPM=1 tools/scripts/build-all.sh`, comme montré ci-dessous).

### Option A : le `.deb` package (recommandé)

Debian/Ubuntu uniquement pour l'**hôte** — les machines auto-hébergées `nspawnmgr`/base-de-données
qu'il crée sont toujours Debian quoi qu'il arrive, selon le
[§1](#1-vue-densemble-de-larchitecture). Gère le §3 (le compte doté des droits sudo, sudoers, paire
de clés SSH) et crée+démarre la machine auto-hébergée `nspawnmgr` avec Tomcat, les quatre WAR, et
`guacd` déjà installés dedans — le *reste* du §6 n'est cependant pas sautable : « Activer HTTPS »
et « Utiliser un port différent » en particulier valent toujours la peine d'être lus (voir « Ce qui
reste manuel après cela » ci-dessous), juste appliqués maintenant à l'intérieur de cette machine
plutôt que sur l'hôte. Continuez au §7 une fois installé.

**Obtenez un `.deb`**, soit en en construisant un vous-même :

```bash
mvn -DskipTests install                          # root -> target/nspawnmgr.war (installed, not just packaged - the next module needs it)
mvn -f auth/pom.xml -DskipTests package          # -> auth/target/auth.war
mvn -f packaging/nspawnmgr-deb/pom.xml package   # -> packaging/nspawnmgr-deb/target/nspawnmgr_*.deb
```

(ou `BUILD_DEB=1 tools/scripts/build-all.sh`, qui fait les trois mêmes étapes — cette variable
d'environnement existe parce que construire un `.deb` a besoin d'un accès réseau pour récupérer le
plugin Maven `jdeb` à la première utilisation, ce à quoi une construction de développement simple
ne devrait pas être forcée), soit en installant un paquet pré-construit depuis quel que soit
l'endroit où votre équipe le publie — la propre CI de ce dépôt (le job `publish-deb` de
`.gitea/workflows/build.yml`) publie chaque construction réussie vers un registre de paquets Debian
Gitea comme référence fonctionnelle si vous voulez mettre en place la même chose pour votre propre
fork/instance (nécessite un secret Actions de dépôt `PACKAGE_REGISTRY_TOKEN`, un jeton d'accès
Gitea avec la portée écriture-de-paquet — voir le propre commentaire de ce job dans le fichier de
workflow).

**Installez-le :**

```bash
sudo apt install ./nspawnmgr_0.4.0_all.deb   # pulls in openssh-server, openssl, dnsmasq, systemd-container - not a JRE, not tomcat9
```

Ni `tomcat9` ni `guacd`/`guacamole-tomcat` ne sont dans les `Depends:` de ce paquet — la propre
disponibilité de `tomcat9` chez apt varie assez selon la version, et `guacd`/`guacamole-tomcat` ne
sont empaquetés sur aucune version actuelle du tout (voir la propre note de
`packaging/nspawnmgr-deb/debian/control`). `tomcat9`, `guacd`, et `guacamole.war` sont tous
empaquetés à la place et n'ont besoin de rien de votre part (voir §6 et §7) — la seule étape
manuelle restante au §7 est l'extension d'authentification soutenue par base de données, puisque
cela a genuinement besoin d'identifiants que vous seul avez.

**Ce qui vient de se passer, automatiquement** (voir `packaging/nspawnmgr-deb/debian/postinst` et
`nspawnmgr-bootstrap-app-machine.sh` pour les scripts exacts) :

- Un compte système `nspawnmgr_exec` a été créé sur l'**hôte** ; un mot de passe aléatoire a été
  généré pour lui (première installation seulement — non touché lors d'une mise à niveau) et écrit
  dans `/etc/nspawnmgr/nspawnmgr.env` (c'est le mot de passe sudo « secret stocké » du §3 — voir le
  §3 pour ce que cela signifie et comment basculer vers le mode approbation admin à la place) ; une
  paire de clés SSH a été générée et installée dans le `authorized_keys` de ce compte quel que soit
  le mode. La division de niveau NOPASSWD/mot-de-passe du §3 → `/etc/sudoers.d/nspawnmgr_exec`,
  validée avec `visudo -cf` avant d'être digne de confiance.
- Le pont partagé (`nspawnbr0`) et dnsmasq ont été mis en place sur l'hôte, comme pour tout autre
  conteneur géré — voir « Résolution des conteneurs par nom » ci-dessus.
- `debian-minimal` a été fabriqué (le même tarball que « Set up debian-minimal » sur
  `/admin/templates` produirait) et cloné dans une machine fraîche nommée `nspawnmgr`.
- Alors qu'il n'était encore qu'un rootfs extrait, pas encore démarré : un JRE, le tarball Apache
  Tomcat 9.0.120 empaqueté, les quatre WAR
  (`nspawnmgr.war`/`auth.war`/`guacamole.war`/`ROOT.war`), et le paquet `guacd` autonome (propre
  OpenSSL 3.x, FFmpeg minimal, FreeRDP2, libssh2) ont été installés directement dans le propre
  système de fichiers de cette machine — utilisateurs système `tomcat`/`guacd` créés dedans, les
  webapps `manager`/`host-manager`/`examples`/`docs` retirées, `GUACAMOLE_HOME` amorcé avec un
  `guacamole.properties` minimal pointant vers le propre `guacd` de cette même machine, et
  `guacamole-auth-jdbc` plus les deux JAR de pilote JDBC extraits dedans (tout cela sans besoin
  d'accès réseau — tout est empaqueté, rien n'est téléchargé).
- Une copie réécrite de `/etc/nspawnmgr/nspawnmgr.env` a été écrite dans cette machine (`SSH_HOST`
  et `HOST_PUBLIC_ADDRESS` repointés vers la propre adresse de `nspawnbr0` au lieu de `127.0.0.1`,
  pour que nspawnmgr puisse atteindre en retour le compte `nspawnmgr_exec` de l'hôte une fois
  démarré), avec une copie de la clé privée SSH.
- Un port hôte libre a été choisi (`8080` d'abord, incrémentant au-delà de tout ce qui est déjà
  utilisé — affiché pendant l'installation) et redirigé vers le propre `:8080` de cette machine via
  une ligne `Port=` dans son fichier `.nspawn`, pour que `http://<cet hôte>:<ce port>/` atteigne
  nspawnmgr exactement comme une installation non auto-hébergée l'a toujours fait.
- La machine a été démarrée. Tomcat à l'intérieur démarre en servant l'assistant de base de données
  de premier démarrage de `ROOT.war` (§4) — il n'y a pas encore de base de données configurée à ce
  stade, comme avant, juste joignable à une adresse sous-jacente différente maintenant.

**Vérifiez que cela a atterri correctement :**

```bash
sudo machinectl list                             # should show "nspawnmgr" running
sudo visudo -cf /etc/sudoers.d/nspawnmgr_exec    # should print "parsed OK"
curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port shown during install>/
```

Plus rien de lié à Tomcat ne s'exécute sur l'hôte lui-même — ne cherchez pas `tomcat9.service` ou
`/opt/tomcat9` là-bas ; les deux vivent maintenant à l'intérieur de la machine `nspawnmgr`
(`sudo machinectl shell nspawnmgr` pour regarder autour à l'intérieur, ou utilisez le propre accès
SSH de nspawnmgr à celle-ci une fois connecté — voir la note du §4 sur son apparition dans la liste
de conteneurs). Le `.deb` n'écrit jamais `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` dans le
`nspawnmgr.env` de cette machine — seulement les paramètres sudo/nom-d'hôte — donc la vérification
curl ci-dessus :

- **`200`** — aucune base de données fonctionnelle encore, donc vous regardez l'assistant de
  configuration au premier démarrage décrit dans « Assistant de configuration au premier démarrage »
  du §4. C'est l'état normal juste après une installation fraîche du `.deb` ; remplissez l'assistant
  pour continuer.
- **`302`** (redirection vers `/nspawnmgr/`) — une base de données fonctionnelle est déjà
  configurée. Suivez-la et attendez-vous à un autre `302` (vers la page de connexion) si
  l'application réelle a démarré normalement, ou un `404` si ce n'est pas le cas : le contexte
  Spring de nspawnmgr a échoué à démarrer. Vérifiez `sudo machinectl shell nspawnmgr journalctl -u
  tomcat9` avant de supposer que le paquet lui-même est cassé (la propre page « View log » de
  l'interface web nspawnmgr n'aidera pas ici — nspawnmgr lui-même n'est jamais arrivé assez loin
  pour démarrer) ; c'est généralement une valeur manquante/incorrecte dans le propre
  `/etc/nspawnmgr/nspawnmgr.env` de cette machine (le §9 couvre ce que chaque paramètre signifie).

**Ce qui reste manuel après cela** : pointer l'assistant de premier démarrage (§4) vers un serveur
MySQL/PostgreSQL — il crée à la fois les bases de données `nspawnmgr` et `guacamole`, exécute les
schémas des deux applications, et câble pour vous l'extension `guacamole-auth-jdbc` de Guacamole,
mais vous devez quand même l'exécuter une fois et devez quand même créer le compte admin Guacamole
par la suite ; au moins un modèle de conteneur (« Modèles de conteneurs » du §2 — rien ne peut être
créé tant qu'un n'existe pas ; une installation fraîche démarre avec zéro, donc le bouton en un
clic « Set up debian-minimal » de `/admin/templates` est disponible immédiatement) ; réviser/
ajuster le reste de `/etc/nspawnmgr/nspawnmgr.env` par rapport au §9 (URL de base Guacamole, etc. —
le fichier généré remplit l'identifiant sudo, `APP_SECRET_KEY`, et
`USER_ID_URL`/`AUTH_LOGIN_URL` pointés vers le propre `auth.war` empaqueté de cet hôte, mais pas la
configuration applicative qui n'a pas de valeur par défaut auto-générée sensée) ; activer HTTPS
(« Activer HTTPS » du §6 — le `.deb` laisse Tomcat en HTTP simple par défaut, comme le chemin
manuel ; fortement recommandé si vous utilisez le mode approbation admin, selon cette section) ; et
la vérification (§10).

`postrm` ne supprime délibérément jamais `nspawnmgr_exec` ni `/etc/nspawnmgr` lors de la
suppression/purge du paquet — ce compte est le seul identifiant à travers lequel vos conteneurs
sont joignables.

**Pour mettre à niveau une installation existante vers une construction de paquet plus récente**
(un correctif de bug, pas une installation fraîche) :
`sudo /usr/lib/nspawnmgr/upgrade-nspawnmgr.sh <chemin-vers-le-nouveau-fichier-paquet>`. Un simple
`apt install`/`dnf install`/`pacman -U` — ou même `apt install --reinstall` — ne suffit pas à lui
seul : ceux-ci peuvent silencieusement ne rien faire si la chaîne de version installée enregistrée
n'a pas changé, ce qui compte puisque chaque construction au sein d'un cycle de développement
republie sous la même version fixe. Ce script installe le fichier paquet donné directement à la
place (applique toujours son contenu, indépendamment de la version enregistrée), ce qui redéclenche
à son tour la propre post-installation du paquet — et celle-ci appelle toujours
`nspawnmgr-bootstrap-app-machine.sh`, qui réconcilie entièrement le contenu de la machine
auto-hébergée `nspawnmgr` à chaque appel, pas seulement à la première installation : les quatre WAR
empaquetés, le propre paquet et service de `guacd`, l'unité de service de Tomcat, et le fichier
d'identifiant de reconnexion SSH sont tous rafraîchis, et la machine est arrêtée/redémarrée autour
de cela pour que rien ne soit écrasé pendant qu'il est encore en cours d'utilisation. Son port
redirigé depuis l'hôte existant est préservé à travers la mise à niveau, pas re-choisi. Non
destructif — `/var/lib/machines` (chaque *autre* conteneur) et les deux bases de données sont
laissés complètement tranquilles ; le clone de rootfs de base et les comptes système
`tomcat`/`guacd` à l'intérieur de la machine sont aussi laissés tranquilles (re-toucher cela
pourrait écraser une véritable personnalisation admin, ou échouer purement et simplement lors d'une
seconde exécution) — une montée en *version* de Tomcat spécifiquement a quand même toujours besoin
d'une réinstallation complète, comme avant.

**Pour tout supprimer quand même** (machines de test, repartir de zéro — pas quelque chose à
exécuter sur un vrai déploiement sans y réfléchir d'abord, puisque cela supprime les identifiants
sudo/SSH à travers lesquels vos conteneurs restent joignables) :
`sudo /usr/lib/nspawnmgr/uninstall-nspawnmgr.sh`. Au-delà de ce que `apt purge` fait déjà, cela
supprime aussi `/opt/tomcat9`, `/etc/nspawnmgr`, `/etc/guacamole`,
`/var/lib/nspawnmgr/templates` (`TEMPLATES_DIR` — les tarballs de modèles, y compris tout ce que le
bouton « Set up debian-minimal » a téléchargé ; un fichier de modèle restant après une purge est
exactement ce qui fait échouer la vérification « ne doit pas déjà exister » de ce bouton lors d'une
réinstallation ultérieure), les comptes système `tomcat`/`nspawnmgr_exec`, et tout [paramètre de
démarrage de machine](#démarrage-automatique-au-démarrage-de-lhôte) que nspawnmgr a configuré
(activation d'unité de démarrage automatique, l'extrait exige-une-autre-machine) — c'est de l'état
de fichier d'unité systemd pur, indexé uniquement par nom de machine, non touché par `apt purge` ni
même en supprimant les conteneurs eux-mêmes, et un extrait `Requires=` périmé survivant à une
installation précédente suffit à casser purement et simplement une réinstallation fraîche
(`machinectl start nspawnmgr` échouant avec « A dependency job for
systemd-nspawn@nspawnmgr.service failed. » parce que l'unité qu'il exigeait n'existait plus) — tout
ce qui est ici est ce que `postrm` laisse délibérément derrière lui, pour les cas où ce
conservatisme n'est pas ce que vous voulez. Par défaut, cela ne touche toujours **pas** à la propre
base de données de nspawnmgr, à la propre base de données de Guacamole, ou à `/var/lib/machines`
(vos vrais conteneurs) — seulement la couche de gestion autour d'eux (plus les modèles utilisés
pour les créer) — mais cela demande séparément (sa propre invite oui/non chacune, jamais implicite
avec `--yes`) s'il faut aussi supprimer les bases de données `nspawnmgr`/`guacamole` et leurs
utilisateurs de base de données (supporté uniquement quand `DB_URL` pointe vers
`localhost`/`127.0.0.1`, lu depuis `db.properties`/`nspawnmgr.env` avant que ces fichiers ne soient
supprimés) et s'il faut supprimer chaque conteneur actuellement enregistré auprès de `machinectl`.
Utile pour réinitialiser rapidement un vrai hôte de test entre les itérations, puisque ces deux
étapes sont une vraie perte de données.

### Installation sur Arch Linux

Construction et installation toutes deux vérifiées en direct sur de vrais systèmes de la famille
Arch : `makepkg -f` contre ce `PKGBUILD` exact (le conteneur systemd-nspawn `arch-runner` sur acer
— voir `packaging/ci/arch-runner/`) produit un vrai `nspawnmgr-0.3.0-1-any.pkg.tar.zst` via le job
`arch-package` de `.gitea/workflows/build.yml`, et les propres hooks `pacman -U` +
`nspawnmgr.install` du paquet résultant ont été exercés à plusieurs reprises sur un vrai système
SteamOS (basé sur Arch, compatible `pacman` une fois que `steamos-readonly disable` est exécuté) —
installations fraîches, cycles de désinstallation/réinstallation, et mises à niveau en place via
`upgrade-nspawnmgr.sh` ont tous été confirmés fonctionnels, y compris la machine auto-hébergée
démarrant avec un vrai bail réseau et l'interface web répondant correctement. Un paquet **séparé**,
`packaging/nspawnmgr-steamos/`, existe spécifiquement pour SteamOS (voir ses propres `provides`/
`conflicts` contre celui-ci — installez exactement l'un des deux, jamais les deux) puisque la
petite partition racine de SteamOS a besoin que le stockage soit relocalisé sous `/home` ; ce
simple paquet Arch est ce qu'un hôte Arch non-SteamOS devrait installer à la place. Ce chemin
non-SteamOS — installer ce paquet exact sur un Arch genuinement vanille (par opposition à SteamOS,
qui partage la même mécanique `pacman`/`systemd` sous-jacente mais n'est pas identique) — n'a pas
encore été testé directement ; rapportez ce qui casse si vous essayez.

`packaging/nspawnmgr-arch/` (un `PKGBUILD` + `nspawnmgr.install`, pas un module Maven — aucun
plugin d'empaquetage Arch natif à Maven n'existe) est par ailleurs la même architecture
auto-hébergée que l'option A ci-dessus, juste un format de paquet différent : même configuration de
compte/sudoers/pont/dnsmasq `nspawnmgr_exec`, même machine `nspawnmgr` auto-hébergée (toujours
Debian-minimal quelle que soit la propre distribution de cet hôte — voir
[§1](#1-vue-densemble-de-larchitecture) — un hôte Arch ne change pas ce que la *machine
d'application* auto-hébergée exécute, seulement ce dont l'*hôte nu* lui-même a besoin), mêmes « Ce
qui vient de se passer », « Vérifiez que cela a atterri correctement », et « Ce qui reste manuel
après cela » que l'option A — lisez-les ci-dessus, ils s'appliquent ici inchangés. Les différences
sont étroites :

- **Dépendances** : `openssh`, `openssl`, `dnsmasq` — pas de JRE, pas d'équivalent
  `apache2-utils` (les deux s'installent *à l'intérieur* de la machine d'application
  auto-hébergée, pas nécessaires sur l'hôte nu du tout — voir
  `nspawnmgr-bootstrap-app-machine.sh`), pas d'équivalent `systemd-container`
  (`machinectl`/`systemd-nspawn` sont déjà expédiés dans le propre paquet `systemd` de base d'Arch).
- **Aucune étape de pare-feu** : contrairement à la dérogation DHCP `ufw` du `.deb`, Arch n'expédie
  aucun pare-feu activé par défaut, donc il n'y a rien à contourner. Si vous avez configuré
  `nftables`/`iptables`/`ufw` vous-même, assurez-vous que l'UDP entrant sur le port 67 sur
  `nspawnbr0` est autorisé (la même exigence pour laquelle la propre étape `ufw` du `.deb` existe).
- **La suppression reste conservatrice par défaut** : `pacman -R`/`-Rns` ne donne pas la même
  distinction purge-vs-suppression que `dpkg`/`apt`, donc le `post_remove()` de
  `nspawnmgr.install` fait délibérément aussi peu que le propre comportement par défaut (non-purge)
  de `postrm` — même script `uninstall-nspawnmgr.sh` que le `.deb` gère le nettoyage complet,
  toujours installé au même chemin.

Construire et installer :

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_ARCH_PKG=1 tools/scripts/build-all.sh   # needs `makepkg` on PATH - a real Arch host, or the
                                               # archlinux/devtools container image

sudo pacman -U packaging/nspawnmgr-arch/nspawnmgr-0.4.0-1-any.pkg.tar.zst
```

### Installation sur Fedora/RHEL (RPM)

Construction et installation toutes deux vérifiées en direct sur un vrai hôte Fedora 43 sous
SELinux `Enforcing` (le conteneur systemd-nspawn `fedora-runner` sur acer pour la construction —
voir `packaging/ci/fedora-runner/` — et un invité QEMU `fedora-test-vm` séparé pour la vérification
d'installation) : le vrai flux de bout en bout (assistant de configuration de BD, connexion,
création de conteneur, et mises à niveau répétées en place via `upgrade-nspawnmgr.sh`) a été
confirmé fonctionnel, y compris spécifiquement sous SELinux Enforcing.

`packaging/nspawnmgr-rpm/` (un vrai module Maven — `rpm-maven-plugin` fait genuinement appel à
`rpmbuild`, ce n'est pas du Java pur malgré les apparences) est par ailleurs la même architecture
auto-hébergée que l'option A ci-dessus — même configuration de compte/sudoers/pont/dnsmasq
`nspawnmgr_exec`, même machine `nspawnmgr` auto-hébergée (toujours Debian-minimal quelle que soit
la propre distribution de cet hôte), mêmes « Ce qui vient de se passer », « Vérifiez que cela a
atterri correctement », et « Ce qui reste manuel après cela » que l'option A. Les différences sont
étroites :

- **Dépendances** : `openssh-server`, `openssl`, `dnsmasq`, `systemd-container`, et
  `iptables-nft` — le paquet de Fedora soutenu par nftables qui fournit réellement
  `/usr/bin/iptables` (le nom de paquet `iptables` simple n'existe pas sur Fedora ; la bascule
  d'internet sortant par conteneur a besoin d'un vrai binaire `iptables` quel que soit le backend).
- **Dérogation firewalld** : Fedora expédie `firewalld` actif par défaut. L'installation ajoute
  `nspawnbr0` à la zone `trusted` de firewalld et recharge — sans cela, la politique de zone par
  défaut de firewalld bloque silencieusement les baux DHCP vers les conteneurs, même forme d'échec
  que la propre dérogation `firewalld` de SteamOS (ci-dessous).
- **Module de politique SELinux** : sous le mode `Enforcing`, `systemd_machined_t` a besoin d'un
  petit module de politique personnalisé (`nspawnmgr_machined_cgroup.te`, compilé depuis la source
  au moment de l'installation via `checkmodule`/`semodule_package`/`semodule -i` plutôt qu'expédié
  comme un `.pp` précompilé, pour qu'il corresponde à quelle que soit la version de politique
  réellement en cours d'exécution) accordant `watch` sur les fichiers `cgroup_t` — un écart général
  de politique SELinux sur tout hôte Fedora Enforcing standard, pas spécifique à nspawnmgr, qui
  casse sinon chaque démarrage de conteneur `machinectl`/`systemd-nspawn` avec « Failed to register
  machine: Access denied. ».
- **La suppression reste conservatrice par défaut**, même posture et même script
  `uninstall-nspawnmgr.sh` que les deux autres formats de paquet.

Une mise en garde de topologie d'environnement, pas un bug de code : le nom d'hôte auto-détecté
d'`AUTH_LOGIN_URL` doit être résoluble depuis où que le navigateur se connecte réellement (un choix
de conception délibéré — voir [§9](#9-configurer-nspawnmgr) — qui évite une boucle de connexion à
portée de cookie pire). Cela peut poser problème spécifiquement lors de tests à travers une
topologie NAT/tunnel/redirection-de-port plutôt qu'un vrai nom d'hôte directement joignable ;
ajustez `AUTH_LOGIN_URL` à la main dans ce cas.

Construire et installer :

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_RPM=1 tools/scripts/build-all.sh   # needs a real `rpmbuild` binary (`rpm-build` package) -
                                          # a real Fedora/RHEL host, no cross-platform equivalent

sudo dnf install ./packaging/nspawnmgr-rpm/target/rpm/noarch/nspawnmgr-0.4.0-1.noarch.rpm
```

### Option B : compiler depuis les sources, déployer manuellement

**Ce chemin déploie Tomcat directement sur l'hôte sur lequel vous travaillez — il n'auto-héberge
pas nspawnmgr dans sa propre machine de la façon dont l'option A le fait.** C'est très bien ;
l'auto-hébergement est un choix arbitraire que fait le `postinst` du `.deb`, pas une exigence
absolue — un déploiement construit manuellement, Tomcat-sur-l'hôte, est toujours entièrement
supporté, c'est juste la topologie plus ancienne/simple. Si vous voulez le modèle auto-hébergé sans
le `.deb`, le chemin le plus direct est de lire à travers
`nspawnmgr-bootstrap-app-machine.sh` et de faire à la main ce qu'il fait (fabriquer un modèle, le
cloner, installer un JRE/Tomcat/les WAR dans le rootfs de ce conteneur, etc.) plutôt que de suivre
le §6 ci-dessous, qui déploie Tomcat sur l'hôte lui-même, comme il l'a toujours fait.

Depuis la racine du dépôt :

```bash
mvn -DskipTests package                # -> target/nspawnmgr.war
mvn -f auth/pom.xml -DskipTests package  # -> auth/target/auth.war
```

(`tools/scripts/build-all.sh` fait les deux, plus les modules simulés uniquement pour le
développement — les simulations ne sont pas nécessaires pour un vrai déploiement.) Continuez au §6
pour la configuration manuelle Tomcat/compte/sudoers que le `.deb` aurait sinon faite pour vous.

Le `postinst` du `.deb` crée aussi `/etc/nspawnmgr/auth-live/`, appartenant à `tomcat:tomcat` mode
`750` — le fichier partagé dans lequel `/admin/settings` écrit la configuration en direct d'auth.war
(voir [§9](#9-configurer-nspawnmgr)). Un déploiement manuel a besoin de la même chose, une fois que
le propre utilisateur `tomcat` de Tomcat existe (§6) :

```bash
sudo mkdir -p /etc/nspawnmgr/auth-live
sudo chown tomcat:tomcat /etc/nspawnmgr/auth-live
sudo chmod 750 /etc/nspawnmgr/auth-live
```

## 6. Tomcat 9 (nspawnmgr + Guacamole + auth)

**Cette section décrit le déploiement de Tomcat directement sur l'hôte** — la forme que prend une
installation manuelle (option B du §5). Si vous avez installé via le paquet `.deb`/Arch/RPM
(option A du §5), Tomcat n'est pas du tout sur l'hôte — il est à l'intérieur de la machine
auto-hébergée `nspawnmgr`, déjà configuré par `nspawnmgr-bootstrap-app-machine.sh`, et rien de
cette section ne s'applique ; passez directement au §7.

La propre webapp officielle de Guacamole cible toujours `javax.servlet`, donc elle et nspawnmgr
sont déployés côte à côte dans la **même instance Tomcat 9**.

**Pas une dépendance apt.** Comme `guacd` (§7), la propre disponibilité du paquet apt `tomcat9`
varie assez selon la version de Debian/Ubuntu/Mint pour que ce projet empaquette une distribution
binaire Apache Tomcat officielle vanille à la place de s'y fier — une version de correctif actuelle
(9.0.120), pas ce qu'une archive apt se trouve à porter, et ce paquet possède l'instance entière
lui-même (`/opt/tomcat9`, son propre utilisateur système `tomcat`, son propre `tomcat9.service`).
**Si une version précédente de ce paquet (qui dépendait bien du `tomcat9` d'apt) est déjà
installée, supprimez d'abord le `tomcat9` de ce paquet** — deux instances Tomcat essayant toutes
deux de se lier à `:8080` échoueront.

Sinon (option B), extrayez le même tarball empaqueté que le `.deb` expédie —
`packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz` dans un checkout du dépôt — plutôt
que de télécharger une copie fraîche vous-même, pour qu'une installation manuelle corresponde
exactement à la version de correctif contre laquelle ce projet est testé :

```bash
sudo mkdir -p /opt/tomcat9
sudo tar -xzf packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz -C /opt/tomcat9 --strip-components=1
sudo chmod +x /opt/tomcat9/bin/*.sh
```

Exécutez Tomcat comme son propre utilisateur système non privilégié, sans sudo (jamais root, et
délibérément pas le même compte que le [§3](#3-le-compte-ssh-doté-des-droits-sudo)) :

```bash
sudo useradd -r -M -d /opt/tomcat9 -s /usr/sbin/nologin tomcat
sudo chown -R tomcat:tomcat /opt/tomcat9
```

**Si vous avez fait le [§3](#3-le-compte-ssh-doté-des-droits-sudo) avant ceci** (l'ordre
documenté), revenez en arrière et rendez la paire de clés SSH qu'il a générée
(`SSH_PRIVATE_KEY_PATH`, par défaut `/etc/nspawnmgr/ssh_id_ed25519`) lisible par cet utilisateur
`tomcat` maintenant qu'il existe — `SshRemoteExecutor` ouvre ce fichier directement depuis
l'intérieur du propre processus de Tomcat à chaque opération privilégiée, et la clé est créée
`root:root` mode `600` (aucun accès de groupe du tout) puisque `tomcat` n'existe pas encore à ce
stade :

```bash
sudo chown root:tomcat /etc/nspawnmgr/ssh_id_ed25519
sudo chmod 640 /etc/nspawnmgr/ssh_id_ed25519
```

Sauter cela laisse chaque opération privilégiée échouer avec « Failed to establish SSH connection
to 127.0.0.1:22 » — un problème de permissions, pas de connectivité, malgré le libellé.

Le tarball officiel embarque les webapps `manager`/`host-manager`/`examples`/`docs` que le propre
paquet `tomcat9` de Debian répartit en sous-paquets séparés, non installés par défaut ; le
`postinst` du `.deb` les retire à la première installation pour la même raison — une véritable
surface d'attaque évitable si laissée déployée sans configuration — cela vaut la peine de le faire
à la main ici aussi :

```bash
sudo rm -rf /opt/tomcat9/webapps/manager /opt/tomcat9/webapps/host-manager \
       /opt/tomcat9/webapps/examples /opt/tomcat9/webapps/docs
```

Déployez nspawnmgr :

```bash
sudo cp target/nspawnmgr.war /opt/tomcat9/webapps/nspawnmgr.war
```

nspawnmgr, Guacamole, et `auth` (§8) prennent chacun leur propre chemin de contexte ci-dessous —
aucun d'eux ne peut réclamer la racine du serveur sans abandonner ce chemin — donc déposez une
minuscule page de redirection statique pour le simple `http://<nom-hôte>:8080/`, en utilisant le
propre `site/root-index/index.html` de ce dépôt comme référence (redirige vers `/nspawnmgr/`) :

```bash
sudo mkdir -p /opt/tomcat9/webapps/ROOT
sudo cp site/root-index/index.html /opt/tomcat9/webapps/ROOT/index.html
sudo chown -R tomcat:tomcat /opt/tomcat9/webapps/ROOT
```

Définissez `SPRING_PROFILES_ACTIVE=prod` (plus toute autre variable d'environnement du
[§9](#9-configurer-nspawnmgr)) dans ce qui enveloppe le démarrage de Tomcat (le `Environment=`/
`EnvironmentFile=` d'une unité systemd, ou `bin/setenv.sh` sous `CATALINA_OPTS` — mettez entre
guillemets chaque valeur `-D` si elle contient un `;`, puisque `catalina.sh` réévalue
`$CATALINA_OPTS` comme une ligne de commande shell et un `;` non échappé est analysé comme un
séparateur de commande, tronquant silencieusement le lancement). Sans profil actif, nspawnmgr
utilise `dev` par défaut (H2 en mémoire, exécuteurs simulés) — pas ce que vous voulez ici.

Configurez-le comme un service systemd pour qu'il survive aux redémarrages, par ex.
`/etc/systemd/system/tomcat9.service` (la même unité que le `.deb` installe —
`packaging/nspawnmgr-deb/tomcat9.service` dans un checkout du dépôt est une référence toute prête) :

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

`Type=simple` avec `catalina.sh run` (premier plan) plutôt que `Type=forking` avec
`startup.sh`/`shutdown.sh` — systemd supervise directement la JVM de cette façon, donc un plantage
est détecté et `Restart=on-failure` se déclenche effectivement ; une unité forking sait seulement
si le *script wrapper* s'est terminé, pas si Tomcat lui-même est toujours en vie.

```bash
sudo systemctl enable --now tomcat9
```

### Utiliser un port différent

Tomcat écoute sur `8080` par défaut (le `<Connector port="8080" .../>` de `conf/server.xml`). Pour
le changer, éditez cet attribut `port` directement :

```bash
sudo sed -i 's/port="8080"/port="8180"/' /opt/tomcat9/conf/server.xml
```

Ou utilisez la section **Tomcat** sur `/admin/settings` au lieu d'éditer `server.xml` à la main —
elle lit/écrit le même fichier (localisé via la propriété système JVM `catalina.base` que le propre
script de démarrage de Tomcat définit toujours, donc elle trouve le bon `server.xml` que vous
exécutiez le `tomcat9` empaqueté Debian du `.deb` ou un extrait manuellement sous `/opt/tomcat9`),
passant par le même compte SSH doté des droits sudo et le même script wrapper
`nspawnmgr-write-file.sh` que toute autre opération privilégiée utilise déjà — aucun nouvel octroi
sudoers nécessaire. C'est le **fichier lui-même qui fait autorité**, pas une copie en base de
données : la page montre et édite toujours ce qui est réellement sur disque, donc éditer
`server.xml` à la main directement (comme ci-dessus) et utiliser la page de paramètres sont
entièrement interchangeables — aucun des deux ne devient périmé par rapport à l'autre.

Chaque autre `:8080` dans ce guide (et dans votre propre configuration —
`nspawnmgr.auth.user-id-url`/`AUTH_LOGIN_URL`, `nspawnmgr.guacamole.base-url`, et quelle que soit
l'URL que vous dites aux utilisateurs de visiter) doit être mis à jour pour correspondre — rien ne
dérive automatiquement le port depuis `server.xml`, quelle que soit la façon dont vous le changez.
Sur `/admin/settings`, c'est surtout un clic par champ : chacun de ces champs URL a un bouton
« Refresh hostname/port/protocol » qui le réécrit depuis le port/état-HTTPS actuel de la section
Tomcat plus `host.external-hostname` (§8) — pas besoin d'éditer à la main le port de chaque URL
séparément. Si vous êtes derrière un pare-feu, assurez-vous que le nouveau port est ouvert au lieu
de `8080`. Dans tous les cas, le changement ne prend effet qu'après un redémarrage — utilisez le
bouton Restart Tomcat sur `/admin/settings` (voir ci-dessus) ou `sudo systemctl restart tomcat9`
vous-même.

### Activer HTTPS

Deux options, dans l'ordre de la façon dont la plupart des vrais déploiements font réellement cela :

1. **Terminer le TLS avec un reverse proxy** (nginx, Apache, Caddy, un load balancer cloud) devant
   Tomcat, qui continue d'écouter en HTTP simple sur `127.0.0.1:8080` uniquement (liez-le au
   bouclage dans le `<Connector address="127.0.0.1" .../>` de `server.xml` pour qu'il ne soit pas
   joignable directement). C'est généralement le chemin le plus facile pour le renouvellement de
   certificat (par ex. Certbot/Let's Encrypt) puisque c'est découplé du propre format de keystore
   de Tomcat. Pointez chaque URL `nspawnmgr.*`/`AUTH_LOGIN_URL` de ce guide vers
   `https://<nom-hôte>/...` (quel que soit le port sur lequel le proxy écoute) au lieu de
   `http://<nom-hôte>:8080/...` — c'est le proxy, pas Tomcat, auquel s'appliquent réellement les
   exigences de nom d'hôte/cookie du [§8](#nom-dhôte-et-cookie-de-session-partagé).

2. **Configurer directement un connecteur SSL Tomcat**, si vous préférez ne pas exécuter de reverse
   proxy. Depuis Tomcat 8.5/9, l'élément `<Certificate>` de `<SSLHostConfig>` accepte directement
   un certificat/clé PEM (`certificateFile`/`certificateKeyFile`/`certificateChainFile`) — aucune
   conversion de keystore Java nécessaire, ce qui compte parce que c'est exactement le format que
   les clients Let's Encrypt/ACME (par ex. Certbot) vous remettent
   (`fullchain.pem`/`privkey.pem`). Pointez Certbot vers cet hôte
   (`certbot certonly --standalone -d nspawnmgr.example.com`, ou quel que soit le plugin qui
   correspond à votre configuration) et ajoutez un connecteur à `server.xml` :

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

   Le propre utilisateur système `tomcat` a besoin d'un accès en lecture à
   `/etc/letsencrypt/live/.../*.pem` (les propres répertoires de Let's Encrypt sont généralement
   root-uniquement par défaut — soit relâchez les permissions sur seulement ces deux fichiers, soit
   copiez-les quelque part où Tomcat peut lire, et recopiez-les à chaque renouvellement). Redémarrez
   Tomcat, puis utilisez `https://<nom-hôte>:8443/...` partout dans ce guide au lieu de
   `http://<nom-hôte>:8080/...`. Soit retirez entièrement le connecteur HTTP simple, soit définissez
   son `redirectPort="8443"` pour qu'une requête HTTP égarée soit renvoyée vers HTTPS plutôt que
   servie en clair. Le renouvellement de Certbot ne redémarre pas Tomcat pour vous — ajoutez un
   `--deploy-hook "systemctl restart tomcat9"` (ou un script `renewal-hooks/deploy/`) pour qu'un
   certificat renouvelé prenne réellement effet.

   La section **Tomcat** sur `/admin/settings` construit/édite exactement ce bloc de connecteur
   pour vous — une liste déroulante « HTTPS » plus les deux chemins PEM — utilisant le même
   mécanisme fichier-fait-autorité, script-wrapper-SSH décrit dans « Utiliser un port différent »
   ci-dessus. Elle ne retire jamais le connecteur HTTP simple ni ne définit `redirectPort` pour
   vous, et elle remplace toujours entièrement les chemins de l'élément `<Certificate>` existant à
   l'enregistrement plutôt que de fusionner — si vous avez personnalisé le connecteur au-delà de ce
   qui est montré ici (un type de certificat non-`RSA`, plusieurs entrées `SSLHostConfig`, etc.),
   éditez `server.xml` à la main à la place.

Quelle que soit l'option que vous choisissez, chaque URL `http://` référencée ailleurs dans ce guide
— y compris à l'intérieur de `application.yml`/variables d'environnement, pas seulement ce qu'un
navigateur voit — doit devenir `https://` pour correspondre ; une discordance entre ce avec quoi
nspawnmgr est configuré et ce qui est réellement servi est une source courante de boucles de
redirection ou d'échecs de cookie-non-envoyé.

**Si vous utilisez le mode approbation admin**
([§3](#3-le-compte-ssh-doté-des-droits-sudo)), activer HTTPS ici est fortement recommandé même si
rien d'autre ne vous y a incité : la page d'approbation soumet le mot de passe sudo d'un admin comme
un simple champ de formulaire, et c'est une exposition significativement plus grande sur HTTP en
clair que tout ce que nspawnmgr sert par ailleurs. L'installation par défaut documentée reste en
HTTP — c'est une recommandation pour ce mode spécifique, pas un changement au défaut.

## 7. Guacamole

**Aucun des trois composants de Guacamole n'est un paquet apt sur aucune version actuelle de
Debian/Ubuntu/Mint** : `guacd` et `guacamole-tomcat` renvoient zéro résultat sur bookworm, trixie,
jammy, et noble, et même Debian unstable ne construit `guacd` que pour `ia64`/`riscv64`, pas
`amd64`. Chacun est géré différemment, et aucun d'eux seul ne vous donne une configuration
fonctionnelle :

| Composant | Empaqueté ? | Ce qu'il fait |
|---|---|---|
| `guacd` | **Non.** Le `.deb` empaquette à la place une construction autonome (propre OpenSSL 3.x, un FFmpeg minimal, FreeRDP2, et libssh2 — voir `/usr/share/doc/nspawnmgr/guacd-bundle-README.md` pour savoir exactement pourquoi et comment) et l'exécute comme sa propre unité systemd `guacd.service` — aucun paquet système, aucune étape manuelle, sur n'importe quelle option d'installation. | le démon proxy natif |
| `guacamole-tomcat` | **Non.** Pas empaqueté non plus (c'est la *colle d'empaquetage* qui déploierait normalement `guacamole.war` pour vous) — mais `guacamole.war` lui-même l'est : le `.deb` le déploie directement dans le Tomcat empaqueté, comme `nspawnmgr.war`/`auth.war` (voir ci-dessous). | déploie `guacamole.war` dans Tomcat automatiquement |
| `guacamole-auth-jdbc` | **Non.** Pas un paquet apt, mais empaqueté de la même façon que `guacd` — un tarball téléchargé une fois, vérifié par somme de contrôle, et commis dans `packaging/nspawnmgr-deb/vendor/` (voir `vendor/README.md`), pas récupéré à neuf au moment de l'installation. Le `postinst` du `.deb` l'extrait automatiquement, aucun réseau nécessaire ; les installations manuelles exécutent le même script à la main (voir ci-dessous). **Requis, pas optionnel** — voir ci-dessous. | l'extension JDBC qui donne à Guacamole un backend de stockage de connexions MySQL/PostgreSQL, plus ses scripts de schéma SQL |

`guacamole-auth-jdbc` n'est pas une option parmi plusieurs backends que vous pourriez choisir à la
place — nspawnmgr gère chaque connexion et utilisateur Guacamole à travers l'API REST de Guacamole
(voir « GUACAMOLE_HOME et le backend d'authentification » ci-dessous), et cette API n'existe que
lorsque Guacamole exécute une extension d'authentification soutenue par base de données. Le propre
défaut de Guacamole (`user-mapping.xml`, un fichier XML statique sans API) ne l'expose pas. Sauter
cette étape ne vous donne pas un nspawnmgr fonctionnel avec des fonctionnalités réduites — cela vous
donne un nspawnmgr qui ne peut créer ni gérer aucune connexion de conteneur du tout, puisque chaque
action « donner à cet utilisateur l'accès à ce conteneur » appelle finalement cette API. Même avec
l'automatisation du `.deb`, extraire le tarball n'est que la moitié de ce que l'étape 1 du §7
ci-dessous décrit — le JAR/pilote doivent quand même être copiés dans `GUACAMOLE_HOME` à la main, et
ni `guacd` ni `guacamole.war` étant déployés n'implique que quoi que ce soit de cela soit fait ;
confirmez-le séparément.

### guacd

Si vous avez installé via le `.deb` (option A du §5), c'est déjà fait —
`nspawnmgr-bootstrap-app-machine.sh` a extrait le paquet autonome vers `/opt/guacd-bundle` et
démarré `guacd.service` **à l'intérieur de la machine auto-hébergée `nspawnmgr`**, pas sur l'hôte
(`sudo machinectl shell nspawnmgr systemctl status guacd` pour confirmer) — et passez à
« guacamole.war » ci-dessous.

Sinon (option B, déploiement Tomcat-sur-l'hôte —
[§6](#6-tomcat-9-nspawnmgr--guacamole--auth)), vous avez besoin d'un vrai binaire `guacd` de quelque
part, puisque apt n'en fournira aucun sur aucune version actuelle. Le chemin le plus direct est de
réutiliser la même construction autonome que le `.deb` expédie :
`packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz` dans un checkout du dépôt (ou construisez votre
propre copie en suivant la recette de `packaging/nspawnmgr-deb/vendor/README.md` — elle documente
chaque étape, y compris deux vrais pièges qui ont coûté du temps réel à trouver : CMake mettant
silencieusement en cache un chemin OpenSSL périmé à travers les reconfigurations, et `-Wl,-rpath`
ne suffisant pas à lui seul sans un `-L` correspondant). Extrayez-le et installez l'unité systemd de
la même façon que `postinst` le fait :

```bash
sudo tar -xzf packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz -C /opt
sudo adduser --system --home /nonexistent --no-create-home --group guacd
sudo cp packaging/nspawnmgr-deb/guacd.service /etc/systemd/system/guacd.service
sudo systemctl daemon-reload
sudo systemctl enable --now guacd
```

### guacamole.war

Si vous avez installé via le `.deb` (option A du §5), c'est déjà fait aussi —
`nspawnmgr-bootstrap-app-machine.sh` a déployé `packaging/nspawnmgr-deb/vendor/guacamole-1.5.5.war`
(la même version officielle Apache, téléchargée une fois et vérifiée par somme de contrôle, pas
récupérée à neuf au moment de l'installation) via un descripteur de contexte pointant vers
`/usr/share/nspawnmgr/guacamole.war` **à l'intérieur de la machine auto-hébergée `nspawnmgr`**, aux
côtés de `nspawnmgr.war`/`auth.war`. Confirmez avec
`curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port redirigé>/guacamole/` (attendez-vous
à `200`, ou une redirection dans le propre flux de connexion de Guacamole) et passez à
« GUACAMOLE_HOME et le backend d'authentification » ci-dessous.

Sinon (option B, déploiement Tomcat-sur-l'hôte), téléchargez et déployez le même fichier vous-même :

```bash
GUACAMOLE_VERSION=1.5.5
curl -fsSL -o guacamole.war \
  "https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/guacamole-${GUACAMOLE_VERSION}.war"
sudo cp guacamole.war /opt/tomcat9/webapps/guacamole.war
```

### GUACAMOLE_HOME et le backend d'authentification

Guacamole a besoin de son propre `GUACAMOLE_HOME` (généralement `/etc/guacamole`) contenant
`guacamole.properties` plus le JAR d'extension `guacamole-auth-jdbc`/le pilote JDBC pour son
**backend de stockage de connexions** — c'est une préoccupation séparée de la propre base de
données de nspawnmgr. **L'assistant de base de données de premier démarrage du §4 fait maintenant
les étapes 1 à 2 ci-dessous automatiquement** (copier le bon JAR d'extension, écrire les propriétés
`<fournisseur>-*`, exécuter le schéma) dans le cadre de la configuration de la base de données
`guacamole` — le parcours ci-dessous est pour le faire à la main à la place (aucun accès à
l'assistant, le câblage automatique a échoué et a laissé un avertissement, ou vous changez la base
de données après coup). Si vous avez installé via le `.deb`, ce répertoire et un
`guacamole.properties` minimal (juste `guacd-hostname`/`guacd-port`, pointés vers l'instance
`guacd` que cette même installation a déjà démarrée) existent déjà, appartenant à `tomcat:tomcat` —
créé une fois, seulement à la première installation, pour qu'une édition ultérieure (à la main ou
via l'éditeur Guacamole de `/admin/settings`) survive toujours à une mise à niveau. Sinon (option
B), créez-le vous-même : `sudo mkdir -p /etc/guacamole && sudo chown tomcat:tomcat /etc/guacamole`.
Comme couvert ci-dessus, l'extension d'authentification JDBC elle-même est requise, pas un choix
parmi des alternatives : nspawnmgr gère les connexions/utilisateurs à travers l'API REST de
Guacamole en utilisant un compte admin (`nspawnmgr.guacamole.admin-username`/`admin-password`), et
seul `guacamole-auth-jdbc` expose cette API. Donc :

1. Faites extraire le tarball `guacamole-auth-jdbc` — contrairement à `guacd`/`guacamole-tomcat`
   ci-dessus, il n'y a de paquet apt pour cela sur aucune version, mais comme `guacd`, il est
   empaqueté directement plutôt que téléchargé au moment de l'installation :
   `packaging/nspawnmgr-deb/vendor/guacamole-auth-jdbc-1.5.5.tar.gz` dans un checkout du dépôt est
   le même tarball que le `.deb` expédie, déjà téléchargé une fois et vérifié par somme de
   contrôle contre le propre `.sha256` d'Apache. `install-guacamole-auth-jdbc.sh` l'extrait (aucun
   réseau nécessaire) vers un **emplacement d'installation arbitraire** fixe et indépendant de la
   version, `/etc/guacamole/guacamole-auth-jdbc/` (sous-dossiers `mysql/schema/` et
   `postgresql/schema/`, quelle que soit la base de données que vous finissez par utiliser — le
   tarball expédie les deux). Ce n'est pas un chemin que Guacamole lui-même exige, juste la propre
   convention de nspawnmgr :
   - **Installations `.deb`** : cela s'est déjà exécuté automatiquement, dans le cadre du
     `postinst` — s'il a échoué (par ex. le tarball manque quelque part dans
     `/usr/share/nspawnmgr/`), relancez
     `sudo /usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh` à la main.
   - **Installations manuelles**, ou pour le refaire (par ex. pour monter en version de Guacamole —
     re-vendorez d'abord le tarball) : exécutez
     `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh` depuis un checkout du
     dépôt (drapeaux `--source-tarball`/`--target-dir`/`--force` disponibles — voir le propre
     commentaire d'en-tête du script).

   Dans tous les cas, depuis `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/`, copiez le
   JAR d'extension pour la base de données que vous avez choisie
   (`nspawnmgr.guacamole.data-source`, par ex. `mysql`) dans `GUACAMOLE_HOME/extensions/` —
   toujours une étape manuelle, puisqu'elle dépend d'un choix (quelle base de données) que rien ne
   peut faire pour vous.

   Le propre pilote JDBC (le véritable `java.sql.Driver`, séparé du JAR d'extension ci-dessus —
   `guacamole-auth-jdbc` ne l'embarque jamais) est une histoire différente : nspawnmgr.war
   embarque déjà à la fois les pilotes MySQL et PostgreSQL pour son propre usage de base de
   données non lié (`pom.xml` racine), donc plutôt qu'un second téléchargement séparé,
   `install-guacamole-jdbc-drivers.sh` copie simplement les deux propres JAR de pilote déjà
   construits de nspawnmgr dans `GUACAMOLE_HOME/lib/` — aucun accès réseau nécessaire du tout, et
   aucun mal à ce que les deux soient là même si un seul est réellement utilisé. Comme le tarball
   de schéma ci-dessus, cela s'est déjà exécuté automatiquement dans le cadre du `postinst` du
   `.deb` (meilleur effort — relancez
   `sudo /usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh` si cela a échoué pour une raison
   quelconque) ; pour une installation manuelle, exécutez
   `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh --source-dir target/guacamole-jdbc-drivers`
   depuis un checkout du dépôt après `mvn -DskipTests package`.
2. Exécutez le script de schéma de cette extension contre une base de données que Guacamole
   possède (ce n'est **pas** la même base de données que la propre base de données de nspawnmgr —
   Guacamole a besoin de son propre schéma utilisateurs/connexions). La section Guacamole sur
   `/admin/settings` a un bouton **« Test database connection »** qui fait cela pour vous : elle se
   connecte avec ce qui est actuellement entré dans les champs Database, vérifie si le schéma
   semble configuré (en sondant la table `guacamole_connection`), et sinon, propose d'exécuter
   chaque fichier `.sql` dans un répertoire que vous lui indiquez — le champ « Schema scripts
   directory » a déjà pour défaut
   `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/schema` (correspondant au type de base de
   données sélectionné au-dessus), donc c'est généralement un clic « Test » sans édition si
   l'étape 1 a utilisé l'emplacement arbitraire.
3. Créez le compte admin que nspawnmgr utilisera (`guacadmin`/`guacadmin` est le défaut bien connu
   que l'extension JDBC expédie à la première exécution — changez le mot de passe immédiatement
   dans un vrai déploiement, et mettez à jour `nspawnmgr.guacamole.admin-password` pour
   correspondre).
4. Définissez `guacd-hostname`/`guacd-port` dans `guacamole.properties` (par défaut
   `localhost:4822`, très bien si guacd s'exécute sur le même hôte).

Redémarrez Tomcat après avoir déposé des fichiers dans `GUACAMOLE_HOME` — Guacamole ne recharge pas
les extensions à chaud.

Pointez nspawnmgr vers celui-ci (`nspawnmgr.guacamole.base-url`) une fois qu'il est prêt, par ex.
`http://votre-nom-hôte:8080/guacamole`. Définissez aussi `nspawnmgr.guacamole.home`
(`GUACAMOLE_HOME`, par défaut `/etc/guacamole`) si vous avez utilisé un chemin non par défaut —
c'est ce depuis quoi l'éditeur Guacamole de `/admin/settings` lit/écrit `guacamole.properties` (voir
[§9](#9-configurer-nspawnmgr)). Aucune configuration de permission supplémentaire nécessaire :
nspawnmgr et Guacamole s'exécutent tous deux comme le même utilisateur `tomcat` dans la même
instance Tomcat, et `GUACAMOLE_HOME` appartient déjà à `tomcat` pour le propre usage de Guacamole.

## 8. `auth` (backend de connexion)

`auth.war` est la chose qui vérifie réellement un nom d'utilisateur/mot de passe contre vos comptes
OS (PAM) ou une machine Windows par SMB, et émet le cookie de session partagé auquel nspawnmgr fait
confiance. Il cible `javax.servlet` (Servlet 4.0), comme nspawnmgr et la webapp de Guacamole, donc
il se déploie dans la **même instance Tomcat 9** que le §6 — aucun conteneur de servlet séparé
nécessaire. (Pour l'itération locale rapide uniquement, il peut aussi être exécuté de façon autonome
via `mvn -f auth/pom.xml jetty:run`, qui le démarre sur Jetty sur le port 9092 sans cycle de
reconstruction/redéploiement de WAR — pas quelque chose que vous utiliseriez pour un vrai
déploiement.)

Définissez ceci via des context-params dans `auth/src/main/webapp/WEB-INF/web.xml` (reconstruisez
le WAR après édition) ou les propriétés système correspondantes (`-D...`), documentées dans ce
fichier :

| Paramètre | Propriété système | Objet |
|---|---|---|
| `auth.backend` | `AUTH_BACKEND` | `pam` (par défaut, comptes Linux locaux sur le propre hôte d'auth) ou `smb` (machine Windows distante) |
| `smb.server` | `SMB_SERVER` | Requis si `auth.backend=smb` — l'hôte Windows contre lequel s'authentifier |
| `smb.domain` | `SMB_DOMAIN` | Domaine NTLM optionnel |
| `auth.required-group` | `AUTH_REQUIRED_GROUP` | Optionnel, `pam` uniquement — un groupe Unix ; la connexion est refusée aux utilisateurs authentifiés qui n'en sont pas membres |
| `smb.required-share` | `SMB_REQUIRED_SHARE` | Optionnel, `smb` uniquement — un partage SMB sur `smb.server` ; la connexion est refusée à moins que l'utilisateur n'y ait accès (voir ci-dessous pourquoi c'est une vérification de partage, pas une vérification de groupe) |
| `cookie.name` | — | Doit correspondre au `nspawnmgr.auth.cookie-name` de nspawnmgr (par défaut `nspawnmgr_session`) |

**Pourquoi `smb` se base sur l'accès au partage, pas l'appartenance à un groupe :** Windows
restreint les requêtes de groupe/SAM *distantes* à `BUILTIN\Administrators` par défaut
(`RestrictRemoteSAM`) — cela exclurait les utilisateurs ordinaires de jamais passer une
vérification de groupe, par conception, quels que soient les ajustements de registre. L'accès à un
partage est une opération SMB normale, contrôlée par ACL, sans une telle restriction, donc
accordez/refusez l'accès en définissant des permissions de partage et NTFS ordinaires sur
`smb.required-share` pour les utilisateurs qui devraient/ne devraient pas être autorisés à se
connecter.

**`pam` a besoin que le compte Tomcat ait un accès en lecture à `/etc/shadow`.** Vérifier un mot de
passe via PAM signifie finalement lire le hash de l'utilisateur cible dans `/etc/shadow` (mode
`640`, `root:shadow`) — normalement géré de façon transparente à travers le propre assistant
setgid-`shadow` `unix_chkpwd` de `pam_unix` indépendamment du propre groupe du processus appelant,
mais ce repli n'est pas fiable sur tous les hôtes (une vraie installation a rencontré exactement
cela : la promotion setgid de `unix_chkpwd` n'a silencieusement pas pris effet pour *aucun* appelant
non-root du tout, donc chaque connexion PAM échouait avec un simple « Login failed » et aucune
erreur exploitable dans le propre journal d'`auth.war`). Le `postinst` du `.deb` ajoute `tomcat` au
groupe `shadow` directement (`usermod -aG shadow tomcat`) pour contourner cela — `pam_unix` peut
alors lire `/etc/shadow` lui-même, aucun repli `unix_chkpwd` nécessaire non plus. Une installation
manuelle (non-`.deb`) a besoin de la même chose : `sudo usermod -aG shadow tomcat`, puis redémarrez
Tomcat (l'appartenance à un groupe ne s'applique qu'aux processus démarrés *après* le changement,
pas à un déjà en cours d'exécution). Si les connexions PAM échouent après cela, vérifiez
`/var/log/auth.log` pour la vraie ligne `pam_unix(login:auth)` — c'est la façon la plus directe de
voir ce que PAM lui-même a rejeté, puisque la propre page « Login failed » d'`auth.war` est
délibérément générique (aucun indice d'énumération d'identifiants).

Déployez-le à son propre chemin de contexte `/auth` dans la même instance Tomcat 9 que
nspawnmgr/Guacamole (qui prennent `/nspawnmgr` et `/guacamole`) pour qu'il serve `/auth/login`,
`/auth/userinfo`, `/auth/logout` (correspondant au `nspawnmgr.auth.user-id-url` ci-dessous) :

```bash
sudo cp auth/target/auth.war /opt/tomcat9/webapps/auth.war
```

`tools/scripts/setup-auth-tomcat.sh` est une référence pour exactement cela, adaptée pour les tests
locaux. Les propres pages de connexion/déconnexion d'`auth` construisent leurs liens internes (par
ex. « Try again ») depuis `request.getContextPath()`, pas un chemin codé en dur, donc elles se
résolvent correctement qu'il soit déployé à `/auth` ici ou à la racine du serveur (par ex. via
`jetty:run` pour l'itération locale).

### Nom d'hôte et cookie de session partagé

nspawnmgr, `auth`, et Guacamole **doivent tous être joignables à travers le même nom d'hôte** — le
cookie de session qu'`auth` définit n'est utile à nspawnmgr que si les deux sont sur la même portée
de cookie d'origine. Comme les trois partagent désormais une seule instance Tomcat, c'est en grande
partie automatique (même hôte, même port), mais choisissez quand même un vrai nom d'hôte (pas
`localhost`, à moins que tout ne soit vraiment sur une seule machine que vous n'accéderez jamais
qu'en tant que `localhost`), pointez-le vers l'IP de l'hôte dans le DNS ou `/etc/hosts`, et
définissez-le une fois dans **`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME` —
modifiable en direct sur `/admin/settings`, « External hostname » sous Host ; amorcé automatiquement
au vrai nom d'hôte de cette machine par `setup-sudo-account.sh` lors des installations `.deb`, voir
§5). Ce n'est *pas* le même paramètre que `nspawnmgr.host.public-address` juste en dessous sur cette
page — voir la propre description de ce champ, ou le [§9](#9-configurer-nspawnmgr), pour la
différence.

Partout ailleurs où ce nom d'hôte a besoin d'apparaître est un simple champ URL, pas dérivé
automatiquement — `nspawnmgr.auth.user-id-url` (`http://<nom-hôte>:8080/auth/userinfo`),
`nspawnmgr.guacamole.base-url`, et la page de connexion que les administrateurs/utilisateurs sont
invités à visiter (`http://<nom-hôte>:8080/auth/login?returnTo=...`) — mais `/admin/settings` comble
cet écart : chacun de ces champs URL a un bouton **« Refresh hostname/port/protocol »** qui le
réécrit depuis External hostname ci-dessus plus l'état actuel port/HTTPS de la section Tomcat (voir
§6), donc un changement de nom d'hôte ou de port n'a besoin d'être tapé qu'à un seul endroit avant
de cliquer à travers le reste.

Si vous terminez le HTTPS devant cela, le CN/SAN du certificat doit correspondre à ce nom d'hôte —
une discordance ici est la cause la plus courante de « la connexion fonctionne mais nspawnmgr
montre quand même la page login-required ».

**Naviguez toujours vers nspawnmgr via le même nom d'hôte que `HOST_EXTERNAL_HOSTNAME`/
`AUTH_LOGIN_URL` — pas `localhost`, une adresse IP, ou tout autre alias, même s'il se résout vers la
même machine.** Le cookie qu'`auth.war` émet n'a aucun attribut `Domain`, donc il est limité à
l'hôte:port exact qui a servi la page de connexion — quel que soit ce vers quoi `AUTH_LOGIN_URL`
pointe, pas le nom d'hôte que vous avez initialement tapé. La redirection-vers-connexion de
nspawnmgr envoie toujours `returnTo` vers ce même hôte:port aussi (indépendamment du nom d'hôte
avec lequel vous avez commencé), donc une discordance ici ne boucle pas indéfiniment, mais vous
atterrirez sur le nom d'hôte canonique plutôt que celui que vous avez tapé — plus simple d'utiliser
toujours le bon dès le début.

### La redirection nspawnmgr → auth

Quand nspawnmgr ne peut pas valider un cookie de session, il redirige le navigateur vers
`nspawnmgr.auth.login-url` (variable d'environnement `AUTH_LOGIN_URL`) avec un paramètre de requête
`returnTo` pointant en retour vers la page que l'utilisateur essayait d'atteindre ; `auth.war`
redirige en retour là-bas après une connexion réussie. Si `login-url` est laissé vide, nspawnmgr
montre à la place sa propre page statique « login required » sans redirection — définissez
`AUTH_LOGIN_URL` sur l'URL `/auth/login` d'`auth` (par ex. `http://<nom-hôte>:8080/auth/login`)
pour le flux automatique complet.

## 9. Configurer nspawnmgr

Tous les paramètres vivent sous `nspawnmgr.*` dans `src/main/resources/application.yml`, chacun
surchargeable par une variable d'environnement — voir `site/env/.env.example` pour la liste
complète en tant que variables d'environnement, et `dev_env/application-dev_env.example.yml` pour
les mêmes paramètres en YAML. Les groupes importants :

- **`nspawnmgr.ssh.*`** — le compte doté des droits sudo du
  [§3](#3-le-compte-ssh-doté-des-droits-sudo)
  (`SSH_HOST`/`SSH_PORT`/`SSH_USERNAME`/`SSH_PASSWORD`, hôte toujours `127.0.0.1`), plus
  `SSH_PRIVATE_KEY_PATH`, `SSH_CONNECT_TIMEOUT_MS`, `SSH_STRICT_HOST_KEY_CHECKING`. Laisser
  `SSH_PASSWORD` vide bascule la création de conteneur vers le mode approbation admin et exige que
  `SSH_PRIVATE_KEY_PATH` soit défini à la place (l'authentification de transport SSH a besoin de
  *quelque chose* avec quoi s'authentifier de toute façon).
- **`nspawnmgr.auth.user-is-admin-json`** — JsonPath optionnel pour les rôles admin gérés en
  externe ([§3](#3-le-compte-ssh-doté-des-droits-sudo)) ; laissez vide pour le mode géré par
  l'application par défaut (le tout premier utilisateur devient admin, gérable par la suite sur
  `/admin/users`).
- **`nspawnmgr.guacamole.*`** — `base-url`, `admin-username`/`admin-password`, `data-source`,
  `home` (`GUACAMOLE_HOME`, par défaut `/etc/guacamole`), du [§7](#7-guacamole).
- **`nspawnmgr.auth.*`** — `user-id-url` (valide un cookie existant contre `auth`),
  `cookie-name`, `login-url` (la cible de redirection du §8), réglage de cache/timeout,
  `settings-file` (où le fichier de paramètres d'authentification partagé ci-dessous est écrit —
  doit correspondre au propre `auth.settings-file`/`AUTH_SETTINGS_FILE` d'auth.war, par défaut
  `/etc/nspawnmgr/auth-live/auth-settings.properties`).
- **`nspawnmgr.nspawn.*`** — `templates-dir`, `machines-dir`, `settings-dir`,
  `privileged-scripts-dir` du [§2](#2-prérequis-de-lhôte).
- **`nspawnmgr.dns.upstream-servers`** — liste d'adresses IP littérales séparées par des virgules
  vers lesquelles dnsmasq transfère les recherches non-`.internal`, par défaut `1.1.1.1,9.9.9.9` —
  voir [« Résolution des conteneurs par nom »](#résolution-des-conteneurs-par-nom).
  `hosts-file`/`upstream-servers-file` (les fichiers que `ContainerDnsSyncService` écrit) sont des
  chemins au moment du déploiement, pas modifiables en direct.
- **`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME`) — le nom d'hôte partagé du
  [§8](#nom-dhôte-et-cookie-de-session-partagé) ; ce que les utilisateurs en dehors de cet hôte
  utilisent, et ce que les boutons URL « Refresh » de `/admin/settings` insèrent dans chaque URL
  Guacamole/Auth.
- **`nspawnmgr.host.public-address`** (`HOST_PUBLIC_ADDRESS`) — un paramètre différent, facilement
  confondu avec le précédent, plus utilisé par le chemin SSH/RDP (`guacd` et le propre contrôle de
  disponibilité de nspawnmgr composent maintenant directement l'adresse veth interne d'un conteneur
  MANAGED à la place — voir [Mise en réseau des conteneurs](#mise-en-réseau-des-conteneurs)). Son
  seul consommateur restant est la vérification « HOST_PUBLIC_ADDRESS not loopback » sur la page
  Network Diagnostics ; si cette vérification mérite encore sa place vaut la peine d'être
  reconsidéré, mais cela n'a pas encore été refait. `setup-sudo-account.sh` auto-détecte et amorce
  toujours la vraie adresse de cet hôte ici à l'installation.
- **`nspawnmgr.crypto.secret-key`** (`APP_SECRET_KEY`) — générez avec `openssl rand -base64 32` ;
  utilisé pour chiffrer les secrets que nspawnmgr stocke (par ex. les identifiants Guacamole qu'il
  gère par conteneur). Perdre/faire tourner cela invalide tout ce qui est déjà chiffré avec
  l'ancienne clé.
- **`nspawnmgr.provisioning.*`** — `admin-account-name` (le compte de repli que nspawnmgr crée à
  l'intérieur d'un nouveau conteneur quand le propre nom d'utilisateur de son propriétaire ne peut
  pas être utilisé — voir `Container users` ci-dessous), `rdp-password-length`.
- **`CONTAINER_CLI_EXECUTOR=real`** — doit être `real` pour un vrai déploiement ; `fake` est
  dev/CI uniquement, et ne touche jamais du tout à SSH/sudo/mots de passe indépendamment du mode
  de création de conteneur ci-dessus. Sélectionne quels beans Spring sont câblés au démarrage du
  contexte, donc cela ne peut pas être changé au runtime du tout — pas exposé sur
  `/admin/settings`, délibérément : c'est un choix au moment du déploiement, et étant donné ce que
  `fake` fait (chaque opération de conteneur devient un no-op silencieux), cela ne vaut pas le
  risque de l'exposer comme une bascule au runtime.

Définissez `SPRING_PROFILES_ACTIVE=prod` — cela active les vrais exécuteurs soutenus par SSH au
lieu des simulations en mémoire utilisées pour le développement local.

### Paramètres modifiables en direct (`/admin/settings`)

Un sous-ensemble des groupes ci-dessus peut aussi être changé au runtime sur `/admin/settings`
(admin uniquement) : `guacamole.base-url`/`data-source`,
`host.external-hostname`/`public-address`, chaque champ `auth.*` y compris `http-timeout-ms`,
`provisioning.admin-account-name`/`rdp-password-length`, `nspawnmgr.ssh.*`, `nspawnmgr.nspawn.*`,
et `nspawnmgr.dns.upstream-servers`. Ceux-ci prennent effet immédiatement pour chaque
requête/allocation suivante — `SettingsService` garde un instantané en mémoire rafraîchi au moment
où un changement est enregistré, pas une lecture en base de données par requête. Une exception,
signalée sur la page elle-même :

- **`nspawnmgr.nspawn.privileged-scripts-dir`** prend effet immédiatement comme tout le reste dans
  son groupe, mais le changer *sans aussi mettre à jour* les chemins codés en dur de
  `/etc/sudoers.d/nspawnmgr_exec` pour correspondre casse **chaque** opération privilégiée
  (démarrage/arrêt de conteneur, synchronisation d'accès sortant, Restart Tomcat ci-dessous) —
  sudo échoue de façon sûre, refusant simplement le nouveau chemin, plutôt que de suivre ce
  paramètre. Il n'y a pas de validation en direct pour celui-ci (c'est un chemin local, peut-être
  pas même encore créé au moment de l'enregistrement) — juste l'avertissement montré sur la page.
- **`nspawnmgr.dns.upstream-servers`** prend effet dans le propre instantané de `SettingsService`
  immédiatement comme tout le reste, mais atteindre le vrai dnsmasq en cours d'exécution est une
  étape de plus par rapport à cela : `ContainerDnsSyncService` ne récupère la nouvelle valeur,
  ne réécrit `/etc/dnsmasq.d/nspawnmgr-upstream.conf`, et ne redémarre dnsmasq que selon son propre
  sondage d'environ 15s — voir [« Résolution des conteneurs par
  nom »](#résolution-des-conteneurs-par-nom) pour savoir pourquoi c'est un `systemctl restart`
  complet, pas juste un reload.

**Tout le reste reste statique/variable-d'environnement/redémarrage-uniquement**, délibérément :
`nspawnmgr.crypto.secret-key`/`nspawnmgr.guacamole.admin-username`/`admin-password` (secrets, plus
faire tourner la clé crypto en direct invaliderait tout ce qui est déjà chiffré avec l'ancienne), et
`CONTAINER_CLI_EXECUTOR` (voir ci-dessus). Les Hosts ne sont pas du tout un paramètre statique — ils
sont entièrement gérés par l'admin via la propre page de détail de chaque host et
`/admin/hosts/new` (voir « Hôtes : machines externes gérées par l'administrateur » ci-dessus).

Chaque changement est validé avant d'être accepté :
- **URL de base Guacamole, URL user-ID d'auth, URL de connexion d'auth** : un sondage de
  joignabilité HTTP en direct (n'importe quelle réponse, même une 404, compte comme joignable —
  cela prouve seulement que l'URL se résout vers quelque chose qui écoute, pas que
  l'authentification elle-même réussit).
- **Les cinq champs JsonPath** : doivent compiler comme des expressions JsonPath valides.
- **Adresse publique de l'hôte** : format uniquement (syntaxe hostname/IP) — délibérément *pas*
  sondée, puisqu'une adresse publique n'est souvent joignable que depuis l'extérieur de cet hôte ;
  se l'auto-sonder ne prouverait rien.
- Le nom du cookie, le TTL de cache, le nom de compte admin, et la longueur du mot de passe RDP
  reçoivent des vérifications de format/plage basiques.
- **`dns.upstream-servers`** : doit être une liste d'adresses IP littérales (IPv4 ou IPv6) séparées
  par des virgules — un nom d'hôte est rejeté, puisque la propre directive `server=` de dnsmasq a
  besoin que ce soit déjà résoluble sans aucun serveur DNS du tout (c'est ce que dnsmasq lui-même
  utilise pour résoudre tout le reste).
- **`ssh.*`** : si un champ SSH quelconque est présent dans le changement soumis, une vraie
  connexion SSH est ouverte avec les paramètres *résultants* (connexion de transport uniquement —
  aucune exécution de commande, donc cela ne dépend pas du fait que l'octroi sudoers NOPASSWD soit
  correct) avant que le changement ne soit accepté. La page de paramètres resoumet toujours chaque
  champ ensemble (comme toute autre section ici), donc en pratique cela s'exécute à chaque
  enregistrement depuis l'interface — de la même façon que les sondages de joignabilité URL
  Guacamole/auth existants le font déjà. Appeler l'API directement avec une charge utile partielle
  qui omet chaque clé `ssh.*` le saute.

#### Section Auth (conditionnelle à la détection de auth.war)

Si auth.war semble joignable (un sondage en direct de `auth.login-url`), `/admin/settings` montre
aussi une section pour la propre configuration de backend d'auth.war : `auth.backend`
(`pam`/`smb`), serveur/domaine SMB, et les portes groupe-requis/partage-requis du
[§8](#8-auth-backend-de-connexion) — aujourd'hui celles-ci ne vivent que dans les context-params/
propriétés système `web.xml` d'auth.war, fixées au moment du déploiement.

Enregistrer cette section (avec le nom de cookie ci-dessus, sur lequel auth.war a aussi besoin
d'être d'accord — c'est lui qui définit réellement le cookie) les écrit dans le fichier de
propriétés partagé à `nspawnmgr.auth.settings-file`. `AuthConfig` vérifie ce fichier **d'abord**, à
chaque requête, avant ses propres context-params/propriétés système — donc un enregistrement ici
prend effet dès la toute prochaine requête d'auth.war, aucun redémarrage d'aucune des deux webapps.
Une valeur vide/non définie ici signifie simplement « pas de surcharge » ; auth.war se replie sur
son propre défaut `web.xml`/propriété-système exactement comme avant que ceci n'existe. L'écriture
du fichier est au mieux effort : si elle échoue (par ex. une installation manuelle a sauté la
configuration de `/etc/nspawnmgr/auth-live/` du [§5](#5-installer-nspawnmgr)), l'enregistrement en
base de données réussit quand même et un avertissement est journalisé — cela ne bloque pas le reste
de la mise à jour des paramètres.

#### Section Guacamole (conditionnelle)

Si Guacamole semble joignable (un sondage en direct de `guacamole.base-url`), `/admin/settings`
montre aussi un éditeur structuré pour `guacamole.properties` (à `nspawnmgr.guacamole.home`) :
des champs individuels pour `guacd-hostname`/`guacd-port`/`guacd-ssl`, plus un sélecteur de type de
base de données (MySQL/MariaDB ou PostgreSQL) qui révèle chaque champ que l'extension
`guacamole-auth-jdbc` correspondante supporte — connexion, SSL/TLS, politique de mot de passe,
limites de concurrence par connexion, intégration d'authentification externe, et application de
fenêtre d'accès. Les libellés de champ et le texte d'aide sont tirés directement du [manuel Apache
Guacamole](https://guacamole.apache.org/doc/gug/configuring-guacamole.html) (pages d'extension
d'authentification [MySQL](https://guacamole.apache.org/doc/gug/mysql-auth.html) /
[PostgreSQL](https://guacamole.apache.org/doc/gug/postgresql-auth.html)), pas inventés localement.

Charger la page lit le fichier existant et pré-remplit chaque champ, y compris tout mot de passe
déjà défini (rendu dans un `<input type="password">` masqué standard, comme changer un identifiant
enregistré ailleurs dans cette application — pas visible en clair à l'écran, mais notez que c'est un
choix de conception délibéré : contrairement au reste de `/admin/settings`, qui garde les secrets
entièrement hors de la surface d'édition en direct, tout l'intérêt de cet éditeur est de laisser un
admin voir et ajuster une configuration de BD Guacamole existante sans se connecter en SSH).
Enregistrer ne touche que les clés documentées ci-dessus : cela vide les clés de l'extension de base
de données que vous *n'avez pas* sélectionnée (pour que le fichier n'accumule pas de configuration
périmée d'un choix précédent) et préserve toute autre clé déjà dans le fichier intacte (par ex. les
propres paramètres d'une extension ajoutée à la main). Enregistrer ne redémarre **pas** Tomcat —
Guacamole ne verra pas le changement tant que vous ne le faites pas (`sudo systemctl restart
tomcat9`).

#### Rapport de paramètres

« Download settings report » produit un fichier texte brut avec chaque paramètre de la page (plus
les `DB_URL`/`DB_USERNAME`/`DB_VENDOR` persistés de l'assistant de base de données et les valeurs
de fichier actuelles de l'éditeur structuré Guacamole), groupé de la même façon que la page
elle-même. Chaque valeur en forme de mot de passe — `ssh.password`, `DB_PASSWORD`, toute clé
Guacamole `*-password` — est remplacée par un `********` littéral : le rapport confirme *qu'*une
valeur est définie, jamais ce qu'elle est.

#### Redémarrer Tomcat

Déclenche `sudo systemctl restart --no-block tomcat9` sur le même compte SSH doté des droits sudo
et le même octroi sudoers NOPASSWD que toute autre opération privilégiée routinière utilise déjà
(voir [§3](#3-le-compte-ssh-doté-des-droits-sudo)) — le `.deb` expédie automatiquement le script
wrapper requis (`/usr/lib/nspawnmgr/privileged/nspawnmgr-restart-tomcat.sh`) et l'entrée sudoers.
Une installation manuelle (non-`.deb`) doit ajouter les deux à la main : copiez le script depuis
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-restart-tomcat.sh` dans
`nspawn.privileged-scripts-dir`, puis ajoutez son chemin à l'alias `NSPAWNMGR_NOPASSWD` dans
`/etc/sudoers.d/nspawnmgr_exec` (validez avec `visudo -cf` avant de lui faire confiance).

Le redémarrage est déclenché de façon asynchrone (`--no-block` met le job systemd en file d'attente
et revient presque instantanément) plutôt qu'attendu — attendre ne fonctionnerait de toute façon
pas, puisque la requête même demandant le redémarrage est servie par l'instance Tomcat sur le point
de s'arrêter. Après avoir cliqué sur le bouton et confirmé, la page attend 5 secondes, vide le
cookie de session côté client, et recharge — atterrissant de retour sur la page de connexion une
fois que l'application (redémarrée entre-temps) voit le cookie manquant, de la même façon qu'elle
le ferait pour toute autre session expirée.

## 10. Vérifier le déploiement

**Sur une installation `.deb`** (auto-hébergée —
[§1](#1-vue-densemble-de-larchitecture)) : `<nom-hôte>:<port>` ci-dessous signifie le port que
l'installation a affiché pendant `postinst` (8080 sauf s'il est déjà pris), et les commandes
`machinectl list`/vérification-de-journaux ont besoin de
`sudo machinectl shell nspawnmgr <commande>` — Tomcat, `guacd`, et les journaux des deux WAR vivent
tous à l'intérieur de cette machine, pas sur l'hôte. Sur une installation manuelle, option B
(Tomcat-sur-l'hôte), tout ce qui suit s'exécute directement sur l'hôte à la place, comme cela l'a
toujours fait.

1. Confirmez que la machine auto-hébergée `nspawnmgr` est active : `sudo machinectl list` sur
   l'hôte devrait la montrer `running` (et, une fois que vous êtes passé par le §4, sa machine de
   base de données aussi). À l'intérieur, `guacd` et Tomcat
   (`nspawnmgr.war` + `guacamole.war` + `auth.war`) devraient tous deux être en cours d'exécution.
2. Visitez `http://<nom-hôte>:<port>/auth/login` directement et confirmez que vous pouvez vous
   connecter avec le compte initial créé pendant l'assistant du §4 (et, si configuré, qu'un compte
   en dehors de `auth.required-group`/`smb.required-share` est correctement refusé).
3. Visitez `http://<nom-hôte>:<port>/nspawnmgr/` sans aucun cookie présent — vous devriez être
   redirigé vers la page de connexion `auth` et, après vous être connecté, de retour vers
   nspawnmgr. Les machines `nspawnmgr`/base-de-données devraient déjà apparaître comme des
   conteneurs ordinaires dans la liste de conteneurs à ce stade — l'assistant les enregistre
   directement, aucune connexion nécessaire d'abord.
4. Créez un nouveau conteneur à travers l'interface de nspawnmgr et confirmez qu'il démarre
   effectivement (`sudo machinectl list` sur l'hôte devrait le montrer) et qu'une connexion
   Guacamole apparaît pour lui.
5. Vérifiez la propre page « View log » de nspawnmgr (une fois qu'elle est au moins assez avancée
   pour servir des pages), ou `sudo machinectl shell nspawnmgr journalctl -u tomcat9` pour les
   échecs de niveau plus bas, si quoi que ce soit ci-dessus échoue — la plupart des problèmes de
   premier déploiement sont une discordance de nom d'hôte/cookie (§8) ou le compte sudo (§3) n'ayant
   pas réellement d'accès sudo/SSH correctement configuré.

## 11. Opérations du jour 2

- **Journaux** : `<répertoire-tomcat>/logs/catalina.out.<date>.log` pour l'instance Tomcat unique
  (nspawnmgr, Guacamole, et auth y journalisent tous) ; `journalctl -u guacd` pour le démon proxy de
  Guacamole — sur une installation `.deb` (auto-hébergée), les deux vivent *à l'intérieur* de la
  machine `nspawnmgr` (`sudo machinectl shell nspawnmgr <commande>`), pas sur l'hôte. Le `.deb`
  câble la propre sortie standard/erreur standard de Tomcat à travers `rotatelogs`
  (`apache2-utils`) via l'`ExecStart` de `tomcat9.service`, produisant un nouveau fichier daté
  quotidiennement — contrairement à un simple `catalina.sh start`, le `tomcat9.service` de ce
  paquet exécute `catalina.sh run` directement, ce qui ne produit jamais de `catalina.out` non daté
  de lui-même (c'est seulement ce que vous verriez en exécutant Tomcat de façon interactive, par
  ex. le dev stack). Chaque utilisateur connecté peut voir les 100 dernières lignes et le journal
  actuel complet sur la propre page « View log » de nspawnmgr ; les admins peuvent aussi parcourir
  et supprimer des jours individuels tournés depuis là.
- **Redémarrage** : redémarrez Tomcat après avoir changé toute configuration `-D`/variable
  d'environnement — rien de tout cela n'est rechargé à chaud, et comme les trois webapps partagent
  la seule instance, la redémarrer redémarre les trois ensemble. Redémarrez seulement `guacd` après
  avoir changé `guacd-hostname`/`guacd-port` dans `guacamole.properties`.
- **Sauvegardes** : sauvegardez la propre base de données de nspawnmgr (métadonnées
  conteneur/utilisateur), la propre base de données de Guacamole (historique/paramètres de
  connexion), et `/var/lib/machines` (systèmes de fichiers racine des conteneurs) séparément — ce
  sont des magasins indépendants sans intégrité référentielle croisée imposée au-delà de ce que
  nspawnmgr gère au niveau applicatif.
- **Faire tourner `APP_SECRET_KEY`** : il n'y a pas d'outil de re-chiffrement intégré ; traitez cela
  comme une opération de dernier recours, à planifier à l'avance, pas quelque chose à changer
  négligemment sur un système en production.
- **Requêtes de conteneur en attente** (mode approbation admin uniquement) : apparaissent sur
  `/requests`. `DENIED` est actuellement un état terminal — il n'y a pas de possibilité de
  resoumission, l'utilisateur demandeur doit créer un nouveau conteneur depuis zéro.</new_string>
</new_string>

