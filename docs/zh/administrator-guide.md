# nspawnmgr 管理员指南

本指南将从零开始介绍如何搭建一套真正的、可用于生产环境的 nspawnmgr 部署：Linux 主机与
`systemd-nspawn`、数据库、Tomcat、Apache Guacamole、`auth` 登录应用，以及 nspawnmgr 本身。文档假设
你使用单台 Debian/Ubuntu 系家族的 Linux 主机来运行全部组件——这也是本项目自身构建和测试时所采用的
部署方式；如果你使用其他发行版，请自行调整路径/软件包名称。

如需了解本地开发循环(使用模拟环境、无真实容器、无真实 Guacamole)，请参阅 `site/env/README.md` 和
`dev_env/README.md`——本指南讲的是真实部署。

## 1. 架构概述

**nspawnmgr 运行在它自己的一台 systemd-nspawn 机器中**——一个名为 `nspawnmgr` 的自托管 Debian 容器,
由 `.deb` 的 `postinst`(`nspawnmgr-bootstrap-app-machine.sh`)在任何管理员接触这个应用之前自动创建。
只有一小部分固定的东西留在裸机主机上:

| 留在主机上的内容 | 原因 |
|---|---|
| `nspawnmgr_exec`(具备 sudo 权限的 SSH 账户,参见[第3节](#3-具备-sudo-权限的-ssh-账户)) | 创建/管理容器需要在裸机主机上拥有真正的 root 权限——只有这个账户拥有它 |
| 模板与软件包(`/var/lib/nspawnmgr/templates`,管理员软件包缓存) | 每个容器(包括 nspawnmgr 自身)构建所依赖的共享的、主机端存储 |
| `nspawnbr0`(共享网桥)与 dnsmasq | 每个容器(包括自托管的容器)接入的网络 |

其他一切——Tomcat、全部四个 WAR(`nspawnmgr.war`、`auth.war`、`guacamole.war`、`ROOT.war`)以及
`guacd`——都运行在 `nspawnmgr` 机器**内部**,全部位于那台机器上的同一个 Tomcat 9 实例中,各自使用自己
的上下文路径(`/nspawnmgr`、`/auth`、`/guacamole`,以及 `ROOT.war` 对应的 `/`),与以前完全一致——
唯一变化的只是那个 Tomcat 实例*运行在哪里*,而不是这四个 WAR 相互之间的布局方式。关于为什么
nspawnmgr 自身固定使用 Boot 2.7/Tomcat 9(为了匹配 Guacamole 自身的 web 应用,后者无法在未经修改的
Jakarta EE/Tomcat 10+ 上运行),请参见根 `pom.xml` 顶部的注释;同样的理由应用于 `auth` 时,请参见
`auth/pom.xml` 顶部的注释。

由于 `nspawnmgr` 机器没有主机网络访问权限(和其他每个容器一样,只有一条普通的 veth 接入
`nspawnbr0`),`postinst` 还会挑选一个空闲的主机端口(8080,或下一个空闲端口——它会打印出具体是哪个)
并通过其 `.nspawn` 文件中的 `Port=` 行,直接将该端口转发到那台机器自己的 `:8080`——这与
[自定义端口映射](#自定义端口映射与出站访问)对普通容器所使用的机制完全相同。因此浏览器访问
`http://<该主机>:<该端口>/` 仍然能像以往一样到达 nspawnmgr——这种自托管方式对浏览器一侧是完全透明
的。

`auth.war` 的 PAM 后端(默认选项——参见[第8节](#8-auth登录后端))会针对其自身 JVM 所在主机上的本地
操作系统账户进行认证。由于 `auth.war` 现在运行在 `nspawnmgr` 机器内部,这意味着它所使用的账户——
是在[首次启动配置向导](#首次启动配置向导)中创建的,而不是裸机主机上的账户——不需要任何额外的后端代码
或配置就能实现这一点。

数据库同样是自托管的:首次启动配置向导会为你配置它自己的 Debian 数据库机器(参见
[第4节](#4-数据库)),而不是要求你连接到一台已有的服务器。`nspawnmgr` 机器和它的数据库机器,一旦首次
启动向导完成,就会作为普通的、可见的容器出现在 nspawnmgr 自身的容器列表中——参见[第4节](#4-数据库)
中关于这一点的说明。两者也都被设置为[随主机自身启动而自动启动](#随主机启动自动启动),并且
`nspawnmgr` 被设置为要求其数据库机器已经先启动——否则主机重启时可能会在数据库机器尚未就绪的情况下
先启动 `nspawnmgr` 机器,导致它在无法连接数据库的状态下运行,直到有人注意到并手动启动另一台机器为止。

nspawnmgr 自身从不直接运行 `machinectl`/`systemd-run`——不论 Tomcat 本身实际运行在哪里,运行 Tomcat
的账户都没有 sudo 权限。相反,nspawnmgr 会通过 SSH 连接到**裸机主机上一个独立的、具备 sudo 权限的
`nspawnmgr_exec` 账户**,并在那里以 root 身份运行特权命令——常规操作(启动/停止/删除容器、防火墙同步)
无需任何密码即可完成,只有那些风险更高、仅在创建时才会用到的操作(会在全新容器内部以 root 身份运行
模板作者编写的内容,或需要配置一台全新的机器)才需要密码,该密码要么来自存储的配置,要么来自逐次请求
的管理员批准。在打包安装的情况下,这条 SSH 连接指向的是 `nspawnbr0` 自身的固定地址(`10.100.0.1`)
而不是 `127.0.0.1`,因为 nspawnmgr 是从自己的机器内部向*外*连接到主机,而不是自己和自己通信——这是由
`nspawnmgr-bootstrap-app-machine.sh` 自动完成的设置,无需手动配置。设置这个账户是下面步骤中较为重要、
也容易被遗漏的一步([第3节](#3-具备-sudo-权限的-ssh-账户))。

## 2. 主机先决条件

在将要运行容器的 Linux 主机上:

```bash
sudo apt update
sudo apt install -y systemd-container openssh-server
```

`systemd-container` 提供了 `machinectl`、`systemd-nspawn` 和 `systemd-run`——包括
`machinectl import-tar`,nspawnmgr 用它把容器模板克隆进一台新机器(它会与 `systemd-importd` 通信,
后者以套接字激活方式运行,与 `machinectl start` 所用的 `systemd-machined` 方式相同,所以应该无需任何
额外设置就能正常工作)。确认基本功能可用:

```bash
machinectl list-images   # should run without error, even with an empty list
```

nspawnmgr 期望以下两个目录存在,且可被具备 sudo 权限的账户写入(它们通常会在 `systemd-nspawn`/
`machinectl` 首次使用时自动创建,但仍值得确认一下):

- `/var/lib/machines`——容器根文件系统所在位置(`NSPAWN_MACHINES_DIR`)
- `/etc/systemd/nspawn`——每个容器各自的 `.nspawn` 配置文件所在位置(`NSPAWN_SETTINGS_DIR`)

这些都是**真实的、固定的系统路径**——不论 nspawnmgr 自身的配置怎么写,`machinectl`/`systemd-nspawn`
永远不会去别的地方找。不要试图对它们做沙箱隔离。

### 数据库(两个,相互独立——nspawnmgr 和 Guacamole 各一个)

请规划**两个相互独立的数据库**,都部署在同一台 MySQL/MariaDB 或 PostgreSQL 服务器上:一个是
nspawnmgr 自身的用户/容器/设置/模板 schema,另一个是 Guacamole 自身的用户/连接/权限 schema(由
Guacamole 的 `guacamole-auth-jdbc` 扩展单独管理)。**仅支持 MySQL/MariaDB 或 PostgreSQL——没有 H2
选项。**参见[第4节](#4-数据库)——首次启动配置向导会为你创建这两个数据库,并使用固定的既定名称
(`nspawnmgr`/`guacamole`),所以无需提前手动准备。

### 容器模板(基础根文件系统)

nspawnmgr 通过 `machinectl import-tar` 把一个"模板"克隆到 `/var/lib/machines` 中来配置新容器。模板本身
存放在 `TEMPLATES_DIR`(默认 `/var/lib/nspawnmgr/templates`)下,每个后端各有一个子目录——`nspawn/`、
`podman/` 和 `qemu/`(另外两个后端各自的模板格式以及如何填充它们,请参见下面的
["Podman:pod"](#podmanpod)和["QEMU:虚拟机"](#qemu虚拟机)——本节专门讲的是 nspawn 的
`<name>.tar.gz` 文件:即一个根文件系统的普通 gzip 压缩 tar 包,正是 `machinectl import-tar` 本身所消费
的格式)。你需要自己准备至少一个真正可引导的模板——nspawnmgr 不会替你下载或构建这些,唯一的例外是:
`/admin/templates` 提供了三个相互独立的**"设置 X-minimal"**按钮——**debian-minimal**(APT)、
**fedora-minimal**(DNF)、**arch-minimal**(PACMAN)——每个按钮只在对应版本的模板尚不存在时才会显示
(设置其中一个不会隐藏其他的;可以设置任意一个或全部三个)。每个按钮都会从
images.linuxcontainers.org 下载一份真实的(经过校验和验证的)minirootfs,在其中安装并启用一个 SSH
服务器,将其打包为 `TEMPLATES_DIR/nspawn/<flavor>-minimal.tar.gz`,并以其"SSH 已预装"标志注册它——
一键即可获得一个真正可用的模板。这个标志(也可以在任何手工创建的模板上设置,参见其编辑表单)会告诉
容器创建流程该镜像已经安装并启用了 SSH,从而跳过其他模板都需要的、原本冗余的下载/安装/启用步骤。这
不是一个通用的模板管理工具:没有针对自定义名称的等效按钮,并且每个按钮会在其对应版本的模板存在后消失
(与其他模板是否存在无关)。与其他所有仅在创建时才需要的操作一样需要相同的 sudo 权限(第3节)——在
管理员审批模式下,你会被要求内联输入 sudo 密码。具体每个按钮做了什么,请参见
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-{debian,fedora,arch}-template.sh`——
**只有 Debian 那个已经在真实容器上得到确认**;另外两个的验证状态,请参见下面的
["Fedora 和 Arch 模板:验证状态"](#fedora-和-arch-模板验证状态),以及这三个脚本目前共用的双路径
(原生主机方式 vs. chroot 方式)方案。本仓库自身的
`site/templates/nspawn/{debian-minimal,fedora-minimal,arch-minimal,alpine-minimal}` 是完全*不同*的
东西——它们只是一些微小的占位目录(甚至不是 tar 包),仅用于本地开发模式测试(参见
`site/templates/README.md`)——**不要把它们当作真实模板使用**,它们不可引导。

三者中特意没有包含 Alpine:Alpine 的官方 minirootfs 完全没有 systemd/D-Bus(它使用 OpenRC),而
nspawnmgr 运行的每一条容器内命令都要经过 `systemd-run --machine=`,这要求容器自身正在运行 systemd——
基于 Alpine 的容器会永久性地报错"Failed to connect to bus",而不是一个值得重试的临时性启动竞态。要
真正支持 Alpine,需要先在容器内把 systemd 安装并作为 PID 1 正常运行,这在 Alpine 上并不是标准做法,
本项目也尚未对此进行测试。

#### Fedora 和 Arch 模板:验证状态

**debian-minimal 是三个"设置 X-minimal"按钮中唯一在真实容器上得到确认的一个**——在本项目的开发过程
中,它已经被多次创建并实际启动过。**fedora-minimal** 和 **arch-minimal** 具体来说仍未经过验证:真实
的 Fedora/Arch 主机确实存在,并且在本项目的其他地方已被广泛使用(参见上文的 RPM/Arch 软件包安装章
节),但 `nspawnmgr-create-fedora-template.sh`/`nspawnmgr-create-arch-template.sh`——这两个特定的
管理员界面按钮所调用的脚本——从未真正在真实的 systemd-nspawn 容器上被执行过。如果你尝试其中任何一个,
请反馈遇到的问题——以下是一些具体的已知风险点,大致按照出问题的可能性从高到低排列:

- **三个构建脚本(Debian、Fedora、Arch)都会检测 HOST 自身的发行版,并据此选择两种安装路径之一**,
  而不是假设某个固定的发行版。每个脚本都会检查 `command -v apt-get`/`dnf`/`pacman`,寻找它自己
  对应的目标包管理器:如果主机上恰好有匹配的那一个,脚本就会将该工具作为普通的**主机端进程**运行,
  并指向已解压的 rootfs(apt 使用 `-o Dir=`/`-o DPkg::Options::=--root=` 组合,dnf 使用
  `dnf --installroot=`,pacman 使用 `pacman --root=`)。如果主机上根本没有匹配的包管理器(例如
  nspawnmgr 部署在一台 Debian 主机上,却要构建 Fedora 或 Arch 模板,反之亦然),脚本就会改为
  **`chroot` 进刚解压出来的 rootfs,并使用镜像自身内置的那份工具**——把 `/etc/resolv.conf` 复制进去
  (chroot 不共享主机的网络配置),在 chroot 化的安装运行之前绑定挂载 `/dev`/`/proc`/`/sys`/`/run`
  (`/run` 的绑定挂载具体来说是让 `systemd-resolved` 的 NSS 模块在 chroot 内可访问,从而支持 DNS
  解析——没有它,即使 `/etc/resolv.conf` 内容正确,名称解析也可能失败),并在打包 tar 包之前立即
  卸载——这与 `pacstrap`/`arch-chroot`/`debootstrap` 自身的 chroot 阶段所用的技术完全相同。目前只有
  Debian 脚本的主机端分支(Debian 构建 Debian)真正在真实容器上被执行验证过;Debian 脚本的 chroot
  回退分支,以及 Fedora/Arch 脚本的两个分支,都是按规范构建的,但尚未验证——这些特定的构建容器模板
  脚本从未被真正执行过,尽管本项目其他地方确实存在并使用了真实的 Fedora/Arch 主机。
- **arch-minimal 是三者中最具推测性的一个。**已知风险点:(1)下载的镜像的 `/etc/pacman.d/mirrorlist`
  按 Arch 自身的约定,出厂时每一条镜像都是被注释掉的——脚本会显式写入
  `geo.mirror.pkgbuild.com`(Arch 官方的 GeoIP 重定向器);(2)软件包签名验证需要一个已填充的密钥环,
  而这个脚本并没有设置它(真正的 `pacstrap` 会通过 `pacman-key --init`/`--populate` 来设置)——与其
  在没有测试手段的情况下盲目尝试,脚本选择在这次引导安装中禁用签名检查(在目标的 `pacman.conf` 中设
  `SigLevel = Never`),这是一个真实存在、值得了解的安全折衷,尽管对于一个快速起步的开发/测试模板来说
  是合理的;(3)chroot 分支还在 `pacman.conf` 中禁用了 `CheckSpace`——pacman 的磁盘空间检查会通过
  `/proc/self/mountinfo` 把缓存目录解析到一个挂载点,而在 chroot 内部这仍然反映的是主机自身的绝对
  路径,而不是 chroot 重新映射后的 `/`,所以不论实际可用空间如何,检查都会以"剩余磁盘空间不足"这样
  具有误导性的信息失败(这是 pacman 在 chroot 中的一个已知限制);(4)`pacman.conf` 还被加上了
  `DisableSandbox`——pacman 自身基于 Landlock 的下载沙箱(以及它切换到的一个专用非特权 `alpm` 用户)
  一旦容器真正启动并运行 `pacman`,就会被 `systemd-nspawn` 的默认 seccomp 过滤器阻止(相对地,这个
  脚本自身的主机端 `chroot` 完全没有 seccomp 限制)——真实的、正在运行的容器内每一次 `pacman` 调用
  都需要这个设置才能正常工作,而不仅仅是这个脚本自身的构建步骤。
- **`arch-minimal` 完全不支持 RDP。**已在实际环境中确认:`xrdp`/`xorgxrdp` 已经从 Arch 官方仓库中
  移除(在一个刚刚同步过、内容完整的镜像上,`pacman -Ss xrdp` 两者均找不到——不是缓存过期或镜像地址
  错误的问题),而这个应用没有 AUR 支持可以作为后备方案。`arch-minimal` 默认将其自身的 RDP 状态设为
  "不支持"(参见模板管理页面的"RDP"选择器),这实际上会禁用"新建 Nspawn"表单上对应的"启用 RDP"选项——
  只有当未来的 Arch 发行版重新提供该软件包,或者手工修改模板自身的安装命令为其他可用方案时(例如
  仍在 `extra` 仓库中的 KDE 自身的 `krdp`,但它专门绑定于 KDE/Plasma),才应该手动把它重新打开。
- **每个 Fedora 容器都需要绕过其 `sshd` 账户阶段的 PAM 检查才能通过 SSH 连接。**每一次针对真实的、
  已启动的 Fedora 容器发起的 SSH 公钥登录尝试(在 43 和 44 上均已确认——与具体发行版本无关)都会被
  拒绝,报错为 `Access denied for user <account> by PAM account configuration [preauth]`
  (`pam_unix` 的账户阶段,`pam_acct_mgmt`,返回 `PAM_AUTHINFO_UNAVAIL`)——账户、密码和
  `authorized_keys` 全都是真正正确的;`unix_chkpwd` 本身(`pam_unix` 用来安全读取 `/etc/shadow` 的
  setuid 辅助程序)拒绝运行,报错为"This binary is not designed for running in this way"——这是
  Fedora 当前 `shadow-utils` 中一项调用方合法性检查,不能容忍在 `systemd-nspawn` 容器内运行。
  `sshd_config` 中的 `UsePAM no` **无法**绕过这个问题——已在实际环境中确认,sshd 自身的特权监视进程
  在这个构建版本上仍然会无条件调用 `do_pam_account`(sshd 自身会警告
  `'UsePAM no' is not supported in this build`)。真正有效的修复方式是:该脚本把 `sshd` 自身的账户
  阶段指向 `pam_permit.so`(总是成功),而不是 `password-auth` 的 `pam_unix.so`,并且只在
  `/etc/pam.d/sshd` 中修改——不是系统级的 PAM 变更。这只是移除了 SSH 专属的 PAM *账户*阶段检查
  (过期、`nologin` 等等);真正的身份验证(公钥验证)早在这个阶段运行之前就已经独立成功了,所以对于
  这些一次性、临时配置的管理员账户来说,这是一个范围狭窄、刻意为之的取舍。已在 Fedora 43 上确认可以
  正常工作;版本固定在 43(而不是更新的 44)只是因为这是经过端到端验证的确切组合,并不是因为 44 本身
  更差。
- **每个 Fedora 和 Arch 容器的 SSH 提示符都充斥着字面上的转义序列文本**——
  `start=<uuid>;machineid=<uuid>;user=...;hostname=...;bootid=<uuid>;pid=...;type=shell;cwd=...`,
  而不是一个普通的 `[user@host ~]$`。根本原因(已在 Fedora 上实际确认;Arch 表现出同样的症状,并且
  是同一个根本原因,因为这并不是 Fedora 特有的问题——只是取决于哪个发行版的 systemd 版本已经新到会
  自带这个功能,而两者都满足):systemd 257+ 自带
  `/usr/lib/systemd/profile.d/80-systemd-osc-context.sh`(由 `systemd-tmpfiles` 软链接进
  `/etc/profile.d/`),它会在每次提示符上发出一个 OSC 3008"Hierarchical Context Signalling"转义序列;
  Guacamole 自身的终端模拟器无法识别/剥离它,所以它就以字面文本的形式被打印出来。该脚本只有在 `$TERM`
  未设置或为 `dumb` 时才会自我跳过(参见其自身的头部注释),而 Guacamole 的 SSH 客户端会报告一个真实
  的 `$TERM`,所以它总是会触发。在两个构建脚本中都按照文档记录的方式禁用了它(该脚本自身的头部注释
  给出了这个确切的操作步骤):移除 `/etc/profile.d/` 中的软链接,并屏蔽会重新创建它的
  `tmpfiles.d` 片段。
- **在 Fedora 容器上安装 Xfce 桌面管理器彻底失败**——`dnf group install -y "Xfce Desktop"` 报错为
  `No match for argument: Xfce Desktop`。已在实际环境中确认:与 GNOME/KDE 不同,"Xfce Desktop"在
  当前 Fedora 上根本不是一个 comps 组(`dnf group list --available` 并未列出它)——Fedora 实际上是
  提供了一个普通的具名软件包 `xfce4`,它会拉入整个桌面环境。因此改用普通的 `dnf install -y xfce4`,
  这也使得 Xfce-on-DNF 可以被预先抓取(参见上文的"软件包安装:先下载后安装")——与之相对,GNOME/KDE
  自身的 comps 组安装仍然无法被预先抓取,仍然需要容器自身的网络/DNS 才能工作。顺带一提,这次同时把
  同一个预抓取机制从仅支持 APT 扩展为普遍支持 APT/DNF/PACMAN(底层的下载脚本原本就已经支持三者;
  只是决定是否使用它们的那道门原本仅限 APT)——SSH/RDP/VNC 软件包名称现在也会按每种包管理器分别解析
  (例如 Arch 的 SSH 软件包是 `openssh`,而不是 `openssh-server`;它的 RDP 安装还额外需要
  `xorgxrdp`)。
- **那次预抓取范围扩大随后彻底破坏了 Fedora/Arch 的容器创建**——
  `Failed to download DNF packages [openssh-server] ... dnf: not found`,PACMAN 也出现了完全相同
  的失败。已在两者上实际确认。根本原因:`nspawnmgr-download-packages-dnf.sh`/`-pacman.sh`(以及它们
  用于模拟安装的姊妹脚本,被管理员软件包上传流程所使用)直接在*主机*上运行了 `dnf`/`pacman`
  (`--installroot=`/`--root=` 指向容器的 rootfs)——这对 APT 是可行的,因为本项目的 `.deb` 只面向
  Debian/Ubuntu 主机,这些主机总是有 `apt-get`,但 `dnf` 和 `pacman` 都不会出现在这类主机自身的
  `PATH` 上。与模板*构建*(可以回退到对一个尚未启动的 rootfs 进行主机端 `chroot`)不同,一个已经在
  运行的活容器无法用同样的方式安全地 chroot 进入——修复方案改为通过 `systemd-run --machine=`
  在容器*内部*运行 `dnf`/`pacman`,与真正的安装步骤已经使用的非交互式容器内执行原语相同——仅下载,
  不会改变已安装软件包的状态。代价是:DNF/PACMAN 的预抓取失去了 APT 自身跨容器"已缓存且仍然有效的
  软件包永不重新抓取"的复用能力,因为那个共享的主机端缓存目录,在 dnf/pacman 运行于容器自身挂载
  命名空间内部时是不可见的——每一次 DNF/PACMAN 预抓取都会重新完整下载。
- **上面这个容器内修复方案在第一次实际重试时仍然失败**——dnf5 直接拒绝在 `install` 上使用
  `--destdir`(报错为 `Unknown argument "--destdir=..." for command "install" ... available for:
  reposync, download, upgrade`);dnf4 的 `install --downloadonly --destdir=` 组合无法照搬过来。
  dnf5 自身的"仅下载不安装"命令是 `download`,并且默认情况下它只抓取*指定的*那个软件包,而不包含其
  依赖——`--resolve` 才是用来一并拉入完整依赖闭包的参数,这正是 dnf5 中与 `install --downloadonly`
  等效的真正方式。修复方式:`dnf download --resolve --destdir=<dir> <packages>`。与上面的
  `groupinstall`→`group install`/Fedora 上 EPEL 相关问题同样的教训:dnf5 的命令行接口在一些真实的、
  不那么显而易见的地方与 dnf4 有所不同——应当在实际环境中加以确认,而不是假设 dnf4 时代的语法可以
  照搬过来。
- 两个脚本都还会在构建 URL 之前,把 `uname -m` 得到的架构名称(`x86_64`/`aarch64`)翻译成
  images.linuxcontainers.org 自身的命名约定(`amd64`/`arm64`)——遗漏这个翻译会导致 404,不论发行版
  本/构建本身是否正确都是如此。
- 两个脚本都复用了 Debian 脚本所需要的相同的 `net.ipv4.ping_group_range`/DNS 域名 systemd-networkd
  drop-in——这些内容涉及的是 systemd-nspawn 自身生成的容器网络配置,与 Debian 本身没有关系,所以理论
  上*应该*可以照搬到任何基于 systemd 的 rootfs 上,但这只是一个假设,而不是针对 Fedora/Arch 已经通过
  实际环境确认的事实。

手动"安装软件包"流程自身的 DNF 依赖预抓取(通过 `dnf install --assumeno` 模拟,通过
`dnf install --downloadonly` 抓取)带有完全相同的、尚未经过测试验证的注意事项——参见上文的
"上传并安装任意软件包"。

另一种方式是通过 `debootstrap` 手工构建一个 Debian 模板(同样是获取根文件系统的思路,如果你不想从
images.linuxcontainers.org 拉取,或者想要不同的发行版本/架构)——先构建到一个临时目录,然后把它打包
成一个 gzip 压缩的 tar 包,放到真正的 `TEMPLATES_DIR` 位置:

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

`TEMPLATES_DIR/nspawn/` 下的每个 `.tar.gz` 文件都是一个可选择的模板;在 `/admin/templates`
(仅限管理员)注册/编辑对应的 `Template` 行——名称、来源标识符(裸文件名,不含 `.tar.gz`,不含后端
文件夹前缀——例如对应 `TEMPLATES_DIR/nspawn/debian-minimal.tar.gz` 的 `debian-minimal`)、后端、
包管理器,以及可选的安装命令覆盖项。每个模板都记录着一个**后端**
(`domain/ContainerBackend.java`:`SYSTEMD_NSPAWN`、`PODMAN` 或 `QEMU`),各自拥有自己的
`TEMPLATES_DIR` 子目录和文件格式——Podman 和 QEMU 各自的情况请参见下文各节。全新安装从**零**模板
开始——不会预置任何内容——所以这个页面(或下面的"设置 debian-minimal"按钮)正是你获得第一个模板的
真正方式;不论如何,`TEMPLATES_DIR` 下的 tar 包本身仍然需要按上文所述提前单独准备好,这个页面只是
管理指向它的元数据。停用一个模板(而不是删除它)是退役某个模板的正常方式——它会从容器创建下拉菜单中
消失,但由它构建的现有容器不受影响;只有在没有容器再引用它时才允许删除。关于这个页面的仅限管理员的
访问限制实际上是在防范什么,请参见[第3节](#3-具备-sudo-权限的-ssh-账户)的"信任边界"一节。

**模板也可以从一台现有的机器创建**,不仅仅是全新下载:一台已停止容器自己的详情页面上有一个"从这台
机器创建模板"字段(名称 + 可选描述)。它会把该机器当前的根文件系统打包(`tar -czf`,与上面每个构建
脚本已经采用的约定相同)成一个全新的、独立的模板——适合为所有者已经自行定制过的容器做快照,而不是从
头重新配置。特意设计为只在机器处于**已停止**状态时才提供:打包一个正在运行的根文件系统,存在文件在
打包过程中发生变化、导致归档不一致的风险。与仅限管理员的"新建模板"/"设置 X-minimal"页面不同,这是一个
容器所有者可执行的操作(`/api/containers/{id}/create-template`,不在 `/api/admin/**` 之下)——生成的
模板在其他方面是完全相同的,包括同样的 sudo 密码要求,之后也可以像任何其他模板一样被任何人使用。与
"安装软件包"端点类似,目前这个功能只在存储密钥模式下可用(始终传递一个空的 sudo 密码覆盖项)——管理员
审批模式尚未针对这个操作接入。

"新建模板"/"编辑模板"表单的来源名称字段会建议该模板所选后端子目录下当前已存在的每个 `.tar.gz` 文件
的裸文件名(取自 `GET /api/admin/templates/available-source-files?backend=...`,由
`nspawnmgr-list-template-files.sh` 支撑——一个 NOPASSWD、只读的封装脚本,与
`nspawnmgr-list-machine-images.sh` 类似),这样你就不需要记住自己提前准备好的确切文件名。它是一个
浏览器 `<datalist>`,不是一个强制限制的下拉框——该字段仍然接受自由文本,因为建议列表只是尽力而为
(如果 SSH 主机不可达,或目录中还没有任何内容,列表就会为空),不应该在 tar 包真正落地到磁盘之前
阻止注册模板的元数据。

**破坏性变更:**模板存储方式已经从 `TEMPLATES_DIR/<name>` 下一个实时的、已解压的目录树
(通过 `cp -a` 克隆)变为 `TEMPLATES_DIR/nspawn/<name>.tar.gz` 下的一个 gzip 压缩 tar 包
(通过 `machinectl import-tar` 克隆)。在此变更之前创建的 `Template` 行会指向一个 nspawnmgr 已经无法
识别的位置——请删除并重新创建它(例如重新点击"设置 debian-minimal"),或者按照上文所示,手动将任何
手工放置的自定义模板打包到新的位置/格式中。

#### 从 CI/CD 流水线安装/更新模板

对于脚本化的模板管理(由 CI/CD 流水线构建并发布自己的模板),而不是由人工在 `/admin/templates` 中
逐步点击,nspawnmgr 提供的是一个通过 SSH 调用的 CLI,而不是一个 web API——这个应用完全没有
机器对机器的 HTTP 认证机制(Basic 认证和表单登录都被显式禁用了;唯一的登录路径是由你的外部身份服务
支撑的会话 cookie),所以一个面向 CI 的 HTTP 端点就意味着要从头发明一套新的认证机制。这个 CLI 转而
复用了本项目现有的 SSH + sudo 信任模型。

它使用一个**第二个、刻意独立**的具备 sudo 权限的账户 `nspawnmgr_ci`——与 `nspawnmgr_exec` 相互独立
(具体原因参见下文的"信任边界"一节)。在你主动启用之前,它并不存在:

```bash
sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
```

这会创建该账户,锁定密码登录(仅允许密钥认证),并向标准输出打印一个新生成的 SSH**私钥**,且只打印
这一次——请立即将它复制到你 CI 系统自己的密钥存储中;除了公钥之外,主机上不会保留任何内容。之后
如需替换,重新运行时加上 `--rotate-key`(旧密钥会立即失效,不会作为第二个有效凭据继续存在)。

从你的 CI/CD 流水线,通过 SSH 传输 tar 包来安装或更新一个模板(按 `--name` 作为键的更新插入操作):

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-template.sh \
  --name my-template --package-manager APT --description "Built by CI" \
  < my-template.tar.gz
```

`--name` 会成为文件系统路径的一部分(`TEMPLATES_DIR/nspawn/<name>.tar.gz`),并按此规则进行校验
(只允许字母、数字、`-`、`_`)。`--package-manager` 是必填项(`APT`、`DNF`、`APK` 或 `PACMAN`);
`--backend`、`--description`、`--install-ssh-command`、`--install-xrdp-command`、`--rdp-capable`、
`--active` 都是可选的,与管理员表单自身的字段和默认值一致。新的/更新后的 tar 包只有在数据库行确认
写入之后才会被替换到位,所以中途失败绝不会留下一个安装了一半的模板——正在进行的更新会让先前的版本
一直保持可用,直到新版本完全就绪为止。

#### 从 CI/CD 流水线安装/更新软件包

同一个 `nspawnmgr_ci` 账户(无需在上面那一步之外再单独启用)也可以直接发布到
[管理员软件包缓存](#上传并安装任意软件包)中,适用于那种自行构建 `.deb`/`.rpm` 等制品、并希望容器
所有者无需人工上传即可安装它们的 CI 流水线:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-package.sh \
  --package-manager APT --filename my-tool_1.2.3_amd64.deb --description "Built by CI" \
  < my-tool_1.2.3_amd64.deb
```

`--package-manager`(`APT`/`DNF`/`APK`/`PACMAN`/`ISO`——`ISO` 在这里的含义参见
[可移动介质](#可移动介质iso-镜像))和 `--filename` 是必填项(后者不能包含 `/`,也不能以 `.` 开头);
`--description` 是可选的。安装或更新(更新插入)是按 `--package-manager` + `--filename` 两者共同作为
键的——用相同的这两个值重新运行,会替换先前的文件并原地更新其数据库行,与模板安装采用相同的崩溃安全
策略(数据库写入会先于磁盘上旧文件的替换而被确认)。由于 `cached_packages` 要求有一个真实的上传者
账户(`uploaded_by_user_id`),第一个由 CI 安装的软件包会自动创建一个专用的 `nspawnmgr-ci` 伪用户——
在管理员页面以及每个容器的"安装软件包"区域中,它会像一个真正的管理员用户名一样显示为上传者。

### 重启容器

一个正在运行的容器的详情页面上,Stop/Force stop 旁边有一个**Restart**按钮。它运行的是
`machinectl reboot`——是容器自身操作系统的一次干净的原地重启,与"停止再启动"不同:机器注册信息及其
veth 接口从不会被拆除再重建,所以自定义端口映射、出站访问防火墙状态,以及其他任何绑定在那条 veth 上
的东西都保持有效,不需要重新同步。容器在重启期间会经历与全新启动相同的 BOOTING 状态,期间
`ContainerReadinessPollingService` 会等待 SSH(如果启用了 RDP,也包括 RDP)重新恢复可用。

### 暂停与恢复容器

一个正在运行的容器的详情页面上,Stop/Force stop 旁边有**Pause**/**Resume**按钮。与 Stop 不同,这里
不会拆除任何东西:Pause 会针对容器自身的 `systemd-nspawn@<name>.service` 单元运行
`systemctl freeze`,通过内核的 cgroup 冻结器(systemd 246+)就地挂起其 cgroup 中的每一个进程;Resume
则运行 `systemctl thaw` 来撤销这一操作,让进程从中断的地方继续执行。`machinectl` 本身没有原生的
暂停/恢复概念——这是现代 systemd 原生的等效实现,与 `systemctl freeze`/`thaw` 已经为任何其他单元类型
提供的机制相同。

通过 `machinectl start` 启动的容器(nspawnmgr 始终采用这种方式启动容器)是直接作为
`systemd-nspawn@<name>.service` 单元运行的,不存在单独的 `machine-<name>.scope`——那个服务单元正是
Pause/Resume 所针对的目标。freeze/thaw 可以作用于任何拥有 cgroup 的单元,服务单元也包含在内。
freeze/thaw 的实际*行为*本身(冻结器控制器是否可用/已启用,进程是否真正正确地挂起/恢复)仍然值得在
你重度依赖这个功能时通过实践加以确认。

### 随主机启动自动启动

一台 MANAGED 容器的详情页面(对于没有自己的 `machinectl` 镜像可以启用的 EXTERNAL 主机,不会显示)
有一个**机器设置**面板,包含两个字段:

- **随主机启动自动启动**——一个复选框,由针对容器自身 `systemd-nspawn@<name>.service` 单元的
  `systemctl is-enabled`/`enable`/`disable` 支撑。
- **要求这台机器已经先启动**——一个下拉菜单,列出所有其他 MANAGED 容器的名称,由位于
  `/etc/systemd/system/systemd-nspawn@<name>.service.d/nspawnmgr-requires.conf` 的一个 systemd
  单元 drop-in 支撑(针对所选机器自身单元的 `Requires=`/`After=`,每次变更后都会执行
  `systemctl daemon-reload`)。只有在配合上面的自动启动一起使用时才有意义——它控制的是两台各自独立
  启动的机器之间的启动*顺序*,而不是 Stop/Start 本身会强制执行的运行时依赖。

这两个字段都是**每次页面加载时实时从主机读取,而不是存储在 nspawnmgr 自己的数据库中**——这是刻意
为之的,因为没有任何东西能阻止管理员在 nspawnmgr 之外直接在主机上运行 `systemctl enable`/`disable`,
一个被缓存的值可能会悄悄地与 `systemd` 实际配置的内容不一致。读取它们时如果出现短暂的 SSH 故障,
页面上会显示一条回退提示信息,而不是直接导致整个页面加载失败;保存变更会通过与读取相同的两个封装
脚本(`nspawnmgr-set-machine-autostart.sh`/`nspawnmgr-set-machine-requires.sh`,都是
NOPASSWD——属于常规的、由所有者触发的操作,与 Start/Stop 属于同一等级)。

**自托管的 `nspawnmgr` 机器及其数据库机器**(参见[第1节](#1-架构概述))都会以这种方式自动被设置为
自动启动,并且 `nspawnmgr` 被设置为要求其数据库机器已先启动——否则主机重启可能会在
`nspawnmgr` 自身数据库尚不可达时就把它启动起来。这是由
`ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()` 接好的(同一个自托管基础设施
协调流程,也负责把两台机器都关联到 `debian-minimal` 模板、为它们配置托管 SSH 访问、设置它们在容器
列表中的描述——参见[第1节](#1-架构概述)以及["发现在 nspawnmgr 之外创建的机器"](#发现在-nspawnmgr-之外创建的机器)),
它从 nspawnmgr 自身的 Spring 应用启动的那一刻起就按自己约 30 秒一次的固定周期运行——不依赖任何管理员
操作来触发。一次短暂的失败(以 WARN 级别记录,从不致命)只会在下一次执行时被自动重新捕获处理,无需
管理员介入;同样的协调流程在手动点击**发现机器**时也仍然会运行。

### 容器网络

每个受管容器共享同一个网桥 `nspawnbr0`(生成的 `.nspawn` 文件中的 `Bridge=nspawnbr0`——
`machinectl start` 会在启动时自动把每个容器自身的 veth 纳入其中),而不是各自获得一条隔离的、位于
独立私有子网上的点对点 veth。`nspawnbr0` 及其地址(`10.100.0.1/24`,固定且不可由管理员配置——这是
一个内部约定,不是一个真正的可定制项)是由 `.deb` 自身的 postinst
(`/etc/systemd/network/70-nspawnmgr-bridge.netdev`/`.network`)无条件创建的,不需要你手动设置。
**网络诊断**中有一项只读检查,用来确认它确实已经启动。

**SSH/RDP/VNC 完全不需要任何入站转发。**Guacamole 的 `guacd` 和 nspawnmgr 自身的就绪轮询都会直接
拨号连接一台 MANAGED 容器的内部 veth 地址(其 `host0` 接口,通过 `machinectl`/`nsenter` 实时解析——
参见 `nspawnmgr-get-internal-address.sh`),连接到容器真实的 sshd/xrdp/VNC 端口
(22/3389/5900)。对于这些协议,整个链路中完全没有主机端口转发环节,这就绕开了一个在真实硬件上已经
确认存在的同主机 hairpin-NAT 限制:主机自身发出、经过其自己的 DNAT/转发地址再回到容器的流量,常常
无法被正确地重新 NAT,即使一个真正的外部客户端访问同一个地址+端口是可以正常工作的。容器被分配到的
内部地址会在其到达 RUNNING 状态的那一刻被记录下来(INFO 级别),并且在此后每次重启时都会重新同步到
Guacamole 的连接配置中,以防地址发生变化。

### 图形访问:RDP、VNC 与桌面管理器

"新建 Nspawn"表单上有两个相互独立的复选框,**启用 RDP** 和 **启用 VNC**——可以两者都选、只选一个,
或都不选。勾选任意一个都会显示一个**桌面管理器**下拉菜单(无/GNOME/KDE(`kde-standard`)/Xfce
(`xfce4`)):在一个最小化模板中,没有真正的桌面环境,图形协议的用处很有限,所以选择一个桌面环境会
在配置过程中安装它,如果 RDP 和 VNC 都被选中,则两者共用同一个桌面环境。**无**表示不会额外安装任何
东西。

与下文介绍的提示凭据式访问不同,在创建时选择的 RDP/VNC 会获得一个由 nspawnmgr 创建并存储的真实生成
账户/密码(RDP 复用 SSH 账户,并通过 `chpasswd` 设置一个登录密码;VNC 复用同一个账户,但只通过
`vncpasswd` 设置一个 VNC 专用密码——它本身不需要 Linux 登录密码)。具体的 `vncserver`/`xstartup`/
软件包安装流程,目前只在实际使用中的那一个真实 `debian-minimal`(APT)模板上得到过验证——安装包含
这部分内容的 `.deb` 之后,值得再次确认。

### Podman:pod

在 nspawn 容器之外,"+"菜单中的**新建 Pod**会创建一个真实的、由 `podman` 运行的容器(在机器网格上
带有 `PODMAN` 徽标,与 `NSPAWN`/`QEMU`/`HOST` 并列)——所有权/共享规则相同,卡片网格相同,详情页面
关系也与本文档中其他地方一致。它对任何已登录用户都可用,不受管理员权限限制;仅当尚不存在任何
podman 后端模板时,该链接才会被禁用,与"新建 Nspawn"的处理方式相同。

**创建**(`/containers/new-pod`):名称、模板(仅限 podman 后端模板的下拉菜单)、描述,以及一个可选
的命令——类似于 Dockerfile 中的 `CMD` 覆盖;留空则信任镜像自身内置的命令。如果命令是一个裸的交互式
shell,一旦没有任何东西挂接在它的标准输入上,它会在片刻之内退出,让 pod 落到 STOPPED 状态而不是
"失败"——如果第一个 pod 看起来在创建后立刻消失,值得了解这一点。配置过程
(`ProvisioningService.provisionPod()`)会加载模板的镜像、创建并启动容器、授予所有者访问权限、
解析并持久化其内部地址,并直接落到**RUNNING**状态——与 nspawn 容器不同,这里没有
`BOOTING`/就绪轮询阶段,因为 `podman create` + `start` 是同步的,而且 pod 一开始就不会获得
自动配置的 SSH 凭据可供轮询。

**网络**:pod 与 nspawn 容器共享同一个 `nspawnbr0` 网桥,但是通过一个专用的 podman 网络定义
(`/etc/containers/networks/nspawnbr0.json`,由 `nspawnmgr-configure-podman-network.sh` 写入),
使用 netavark 的**host-local IPAM**,而不是 DHCP——netavark 自身的 DHCP 代理是从主机的网络命名空间
发出的,而内核从不会把那部分流量回环到网桥自己的接收队列上,这是一个已确认的死胡同,而不是一个尚未
探索的选项。地址池与 nspawn 自身的 DHCP 范围是分开的,以避免冲突:pod 获得
`10.100.0.192`–`10.100.0.254`,nspawn 容器保留 `10.100.0.2`–`10.100.0.191`。DNS 在创建时被显式
设置(`podman create --dns 10.100.0.1 --dns-search internal ...`),而不是依赖 pod 永远不会收到的任何
DHCP 下发的配置——podman 自身的 `aardvark-dns` 在这个网络上被专门禁用,以避免与 nspawnmgr 自身的
dnsmasq(它已经绑定在同一个地址上——参见上文的["按名称解析容器"](#按名称解析容器))发生冲突。

**生命周期**与 nspawn 容器完全对等——Start/Stop/Restart/Pause/Resume 全部分派到原生的 podman 命令
(`start`/`stop`/`kill`/`restart`/`pause`/`unpause`),而不是任何 nspawn 专属的机制。一个独立的
**`ContainerLivenessPollingService`** 会按自己约 30 秒一次的周期重新检查每一个 `RUNNING` pod 的真实
podman 状态(以及每一个 `RUNNING` QEMU 虚拟机的真实单元状态——参见下文),一旦发现实际情况与记录不符,
就立即把 nspawnmgr 自身的状态翻转为 `STOPPED`——之所以需要这个,是因为一个 pod 可能会完全自行退出
(一个糟糕或缺失的保活命令,参见上面的命令字段),而应用中其他部分完全不会注意到这一点,因为 pod 完全
跳过了仅适用于 nspawn 的就绪轮询路径。`PAUSED` 状态的 pod 不会被轮询。

**访问**:SSH/RDP/VNC 都是**仅限提示凭据方式**,与 Host 和被发现的容器使用的可达性门控机制相同
([参见上文](#未由-nspawnmgr-自身配置的容器的远程访问))——在客户机自身的服务确实开始监听之后,从 pod
自己的详情页面上按协议逐一启用。pod 永远不会像 nspawn 容器的 SSH 访问那样获得自动生成的凭据。

**Files**通过 `podman mount` 工作,它会把容器的合并覆盖文件系统暴露为一个普通的主机路径——随后
nspawn 容器所使用的同一套浏览/上传/下载代码,就会直接针对这个路径运行。

**Scripts**通过 `podman exec -i <name> sh -s`(以管道方式传入标准输入,返回一个真实的退出码给
nspawnmgr)运行。Abort 是一个比 nspawn 自身的临时单元终止方式更狭窄的近似实现:脚本正文前会加上
`echo $$ > <pidfile>`,Abort 会向记录下来的那个进程组发送 `kill -9`——这是一次真实的进程组终止,但不是
像 nspawn 的中止那样真正的、覆盖整个 cgroup 的终止,代码中已将其记录为一个已知的、刻意为之的窄化,
而不是一个 bug。

**明确不为 pod 提供的功能**(nspawn 容器全部具备):没有自动配置的 SSH/RDP/VNC 凭据,不安装桌面
管理器,不支持自定义入站端口映射,没有出站防火墙开关(pod 已经通过 netavark 拥有真实的网络访问权限——
没有什么可以限制的),不支持挂载 ISO,不支持 `machinectl` 风格的自动启动/依赖配置。

**模板**存放在 `TEMPLATES_DIR/podman/<name>.tar` 下——一个 `podman save` 归档文件,在创建时通过
`podman load` 加载,与 nspawn 的普通 tar 约定不同。填充模板的方式,要么是直接从镜像仓库拉取
(`nspawnmgr-podman-pull-template.sh`),要么是转换一个现有的 nspawn 模板
(`nspawnmgr-podman-convert-nspawn-to-podman.sh`,以及反向操作
`nspawnmgr-podman-convert-podman-to-nspawn.sh`)。目前还没有像已停止的 nspawn 或 QEMU 机器自己的
详情页面那样提供"从这个 pod 创建模板"的便捷功能——只能通过全新拉取或转换获得。

不存在专门针对 podman 后端的自动化测试套件(没有 `*Podman*` 测试类)——它是由针对模拟环境运行的通用
测试套件,加上在 yoga 上进行的手动开发环境测试和实际点击验证来覆盖的。上面提到的 DNS 修复以及
netavark host-local-IPAM 网络方案决策,都已经在实际环境中得到确认(参见
`nspawnmgr-configure-podman-network.sh` 和 `nspawnmgr-podman-create-container.sh` 各自的头部
注释)——进程组终止式的 abort 近似实现是目前主要的、已知的、刻意为之的差距。

### QEMU:虚拟机

在 nspawn 容器和 podman pod 之外,"+"菜单中的**新建 QEMU**会创建一台真实的 QEMU/KVM 虚拟机(带有
`QEMU` 徽标),位于同一个机器网格上,拥有相同的所有权/共享规则。对任何已登录用户都可用;在主机上
未安装 QEMU 时该链接会被禁用(参见诊断页面)。

**创建**(`/containers/new-qemu`):名称;磁盘来源——**空白磁盘**(指定一个以 GB 为单位的大小)或
**来自模板**(克隆一个现有的 QEMU 后端模板自身的磁盘),两者互斥;**处理器类型**;**CPU 数量**;
**内存(MB)**;**网卡**(NIC 设备型号——默认 `virtio-net-pci`,或者对需要特定型号的客户机操作系统
使用 `e1000`/`rtl8139`/`pcnet`,例如 FreeDOS 通常需要 `pcnet`);**指针设备**(默认 `PS/2`,或者
`USB tablet`,后者可以修复图形客户机在 VNC 下的鼠标光标漂移问题——但 DOS 系家族的客户机完全没有 USB
驱动栈,需要使用 PS/2,这也是它作为默认值而不是 USB tablet 的原因);以及一个可选的**启动 ISO**。

`POST /api/containers/qemu` 会校验磁盘大小/模板这两个字段中恰好设置了一个,随后
`ProvisioningService.createPendingQemu()` 会持久化该行记录,`provisionQemu()` 完成实际工作:
克隆模板的磁盘或创建一个全新的空白磁盘、分配一个 VNC 端口、写入虚拟机的 systemd 单元、启动它、生成
并存储一个 VNC 密码,并创建一个匹配的 Guacamole VNC 连接——直接落到**RUNNING**状态,与上文 pod 的
同步启动逻辑相同(没有 `BOOTING`/就绪轮询)。一个独立的、异步的 `QemuAddressPollingService` 会在此
之后尝试解析一个客户机 IP,纯粹是为了 SSH 用途——对于一台刚创建的、磁盘上甚至可能还没有安装客户机
操作系统的虚拟机来说,"尚未就绪,可能会持续相当长一段时间"是预期中的正常状态。

**磁盘创建**(`nspawnmgr-qemu-create-disk.sh`)就是在 `/var/lib/nspawnmgr/qemu-disks/` 下执行一条
普通的 `qemu-img create -f qcow2 <path> <size>G`。与其他任何新的持久化产物一样,需要相同的
PASSWORD 等级 sudo 权限([第3节](#3-具备-sudo-权限的-ssh-账户))——实际启动虚拟机则是一个单独的
NOPASSWD 步骤。

**虚拟机的 systemd 单元**(`nspawnmgr-qemu-write-unit.sh`)是位于
`/etc/systemd/system/nspawnmgr-qemu-<name>.service` 的一个真实的、持久化的单元——它是被重写的,不是
只写一次,在创建时以及此后每当虚拟机处于停止状态、挂载的 ISO 发生变化时(见下文)都会被重写。它之所以
是持久化的,而不是一次性的 `systemd-run` 调用,是因为对它执行一条普通的 `systemctl start/stop`
(这正是 nspawnmgr 驱动一台 QEMU 虚拟机生命周期的方式)只需要一个裸的机器名称,没有任何虚拟机特定的
信息需要重新构建成一条调用命令。它的 `ExecStart` 行涵盖了:内存/CPU 型号/CPU 数量/`-enable-kvm`
标志(KVM 通过 `/dev/kvm` 是否存在来自动检测);作为 virtio 驱动器的 qcow2 磁盘;位于 `nspawnbr0` 上的
网卡,其 MAC 地址是根据虚拟机名称确定性推导出来的(`52:54:00:` + 该名称 md5 哈希值的前 3 个字节——
地址解析脚本必须独立推导出相同的值,因为两个脚本都不会持久化保存它);指针设备相关的标志(PS/2 为空,
USB tablet 为 `-usb -device usb-tablet`);VNC 监听器;一个 Unix 套接字形式的 QEMU 监视器;以及启动
顺序(挂载了 ISO 时为 `-cdrom ... -boot order=d`,否则为 `-boot order=c`)。当 `PATH` 上没有
`qemu-system-x86_64` 时会回退到 `/usr/libexec/qemu-kvm`(这是 Fedora/RHEL 打包上的一个特殊情况,
`nspawnmgr-diag-check-qemu.sh` 已经使用了相同的回退逻辑)。

**VNC 访问**:端口是从一个可由管理员配置的范围中分配的
([`/admin/settings`](#实时可编辑设置adminsettings),经过校验必须从 `5900` 或更高开始——QEMU 自身的
`-vnc host:display` 语法寻址的是一个显示编号,`display = port - 5900`),会挑选尚未被其他虚拟机占用的
最小空闲端口。监听器总是绑定在 `nspawnbr0` 自身的网关地址(`10.100.0.1`)上——与 nspawn/podman 不同,
在那两者中 Guacamole 是直接拨号连接容器自身的内部地址的,而每台 QEMU 虚拟机的 hypervisor 控制台共享
同一个地址,仅通过端口来区分。一个带有生成密码的 Guacamole VNC 连接会在配置阶段自动创建——所有者不
需要做任何启用操作,它就在那里。QEMU 自身不会在重启后持久保留那个密码,所以
`ContainerLifecycleService` 会在每次启动/重启时,通过 HMP 监视器(见下文)重新应用存储的凭据。

**HMP 监视器**仅供内部使用——没有用于发送任意监视器命令的界面。`nspawnmgr-qemu-monitor-exec.sh`
通过 SSH 结合 `socat`,一次一行地把 HMP 命令中继到虚拟机的监视器 Unix 套接字(在 QEMU 停止响应 2 秒
后关闭连接,因为 HMP 的纯文本 REPL 没有干净的、按响应分帧的机制来判断命令是否已完成——这是一个初步
方案,文档中注明尚未针对真实的 `qemu-system-x86_64` 监视器进行验证)。它支撑着:优雅停止
(`system_powerdown`,一个 ACPI 请求——如果尚未安装客户机操作系统则是一个空操作,这是设计使然,不是
bug);Pause/Resume(`stop`/`cont`——QEMU 自身的等效实现,不是 nspawn 容器使用的 cgroup 冻结器);
重新应用上面提到的 VNC 密码;以及实时更换 ISO(`change ide1-cd0`/`eject ide1-cd0`)。

**Files 访问对 QEMU 虚拟机不可用**——与 podman 的 `podman mount` 不同,对于一台存储就是单个 qcow2
磁盘文件的虚拟机而言,没有主机端目录可供浏览,而真正的客户机侧访问(通过虚拟机自身的 SSH 连接进行
SFTP,一旦启用的话)尚未构建。因此 FILES 按钮在一台 QEMU 虚拟机的卡片上是禁用的;计划在未来版本中
支持。

**ISO 挂载**复用了与 nspawn 容器相同的 `PackageManager.ISO` 软件包缓存
([参见上文](#可移动介质iso-镜像))。与 nspawn 的静态绑定挂载(只在虚拟机下次启动时生效)不同,QEMU
可以通过 HMP 监视器**实时更换**已挂载的光盘,同时虚拟机正在运行;它也会把同样的选择单独持久化进单元
文件中(通过上面提到的同一个 `nspawnmgr-qemu-write-unit.sh` 重写机制),这样下次冷启动虚拟机时同样
是正确的。

**模板**:从一个现有的 QEMU 后端模板(`TEMPLATES_DIR/qemu/<name>.qcow2`)克隆虚拟机磁盘,与上文描述
的空白磁盘加 ISO 的路径一样,是完全受支持的——在"新建 QEMU"表单上选择**来自模板**即可。一台已停止
虚拟机自己的详情页面上,也有一个"从这台机器创建模板"字段,与 nspawn 容器采用相同的约定,用来把一台
虚拟机当前的磁盘快照为一个全新的、独立的模板。

**生命周期**通过上面的持久化 systemd 单元,与 nspawn/podman 完全对等,再加上 HMP 监视器用于处理那些
必须请求 QEMU 优雅完成的操作:Start、Force stop 和 Restart 是针对虚拟机自身单元的普通
`systemctl start/stop/restart`;优雅停止以及 Pause/Resume 则如上所述通过 HMP,而不是通过
`systemctl freeze`/`thaw`。

**崩溃协调**:上文为 podman 描述的同一个 `ContainerLivenessPollingService` 也覆盖 QEMU——每一台
`RUNNING` 虚拟机自身的单元都会按同样约 30 秒一次的周期重新检查(`systemctl is-active`),一旦该单元
本身已经停止或消失,nspawnmgr 自身的状态就会立即翻转为 `STOPPED`。**这仍然是一个真实存在、尚未完全
解决的限制**:这只能检测到单元/进程本身消失的情况,无法检测到一种仅发生在客户机操作系统内部的崩溃—
即进程本身仍然存活,但其内部运行的东西已经挂起或死掉——`systemctl is-active` 对此完全没有可见性,
两个后端都没有提供询问的方式。如果某台虚拟机的徽标状态看起来与实际情况不符,值得记住这一点。

同样不存在专门针对 QEMU 后端的自动化测试套件(没有 `*Qemu*` 测试类)——由针对模拟环境的通用套件,加上
手动的开发环境测试和实际点击验证来覆盖;指针设备设置具体已经在 yoga 上针对一台真实的 KolibriOS
虚拟机得到过实际确认。上面提到的 HMP 监视器响应分帧启发式方法,以及
`nspawnmgr-diag-check-qemu.sh` 自身的一些检查项,在它们各自的头部注释中都明确标注为尚未针对真实的
`qemu-system-x86_64` 监视器进行验证。

**发现机器**([参见上文](#发现在-nspawnmgr-之外创建的机器))一次点击即可覆盖全部三个后端——它会分别
对 `machinectl`、`podman` 和 QEMU 自身的 systemd 单元各执行一次独立的扫描,把在其中任何一个发现的、
尚未被跟踪的内容注册进来,如果某个后端在主机上根本没有安装,则会直接跳过它。

### 软件包安装:先下载,而不是直接从实时网络抓取安装

已经确认,从一个正在运行的容器*内部*运行包管理器,即使主机自身的网络/DNS 都工作正常,在解析其自身
镜像源方面也是不可靠的。SSH、RDP、VNC 以及桌面管理器软件包都采用相同的处理方式:nspawnmgr 会先下载
它们(包括完整的依赖闭包,仅下载——此时还不会安装任何东西),然后再在容器*内部*执行真正的安装。适用
于使用默认(未覆盖)安装命令的 **APT、DNF 和 PACMAN** 模板——一个自定义的安装命令覆盖项无法被安全地
解析出软件包名称用于预抓取,因此会回退到今天的仅容器内安装方式(这需要容器自身的网络/DNS 真正能够
工作)。**APK** 被完全排除在外:它自己的本地安装本身就会从已配置的仓库中解析依赖,不需要预抓取
(反正也没有意义——基于 Alpine 的容器在这个应用中目前并不能完全正常工作,见下文)。

**APT 自身的下载步骤运行在主机端**——一个直接指向容器自身 rootfs 目录的进程(`apt-get -o Dir=<rootfs>`),
使用主机自身正常工作的网络——因为 `apt-get` 总是在这台主机自身的 `PATH` 上(本项目的 `.deb` 只面向
Debian/Ubuntu)。**DNF 和 PACMAN 无法这样做**:两者都完全不会出现在这台主机自身的 `PATH` 上,所以
它们自己的下载步骤改为在*容器自身内部*运行,通过 `systemd-run --machine=`(与真正的安装步骤已经
使用的非交互式容器内执行原语相同)——仅下载,与 APT 一样,所以它同样不会触碰 dpkg/rpm/pacman 已安装
软件包的状态。一个后果是:DNF/PACMAN 不具备 APT 自身"已缓存且仍然有效的软件包永不重新抓取"的跨容器
复用能力(那依赖于一个普通的主机端缓存目录,而 dnf/pacman 运行在容器自身的挂载命名空间*内部*时是看
不到它的)——每一次 DNF/PACMAN 预抓取都会重新完整下载。三者都仍然会把依赖闭包缓存在
`/var/cache/nspawnmgr/packages/<manager>/auto/` 下,供管理员软件包页面查看,无论下载本身实际发生在
哪里。

有一个例外:GNOME/KDE 在 DNF 上是通过一个 comps *组*安装的(`dnf group install`),而不是一个普通的
具名软件包——`dnf --downloadonly`(预抓取所使用的方式)没有等效的机制可以提前解析/缓存一整个组的
成员,只能针对单个软件包,所以这两种组合会刻意跳过预抓取,直接落到容器内的组安装(需要容器自身的
网络/DNS,与一个被覆盖的命令所需要的条件相同)。Xfce 没有这个问题——已在实际环境中确认,Fedora 把它
作为一个普通的具名软件包(`xfce4`)提供,根本不是一个 comps 组。

那个真正的容器内安装步骤本身从不会重新执行 `apt-get update`/`dnf` 自身的元数据刷新:这是多余的,
因为预下载步骤在片刻之前已经刷新过索引了(APT 是主机端,DNF/PACMAN 是容器内),所以安装步骤读取到的
内容已经是最新的,而且它需要的每一个软件包都已经放在容器自身的本地缓存中——每个预抓取脚本正是为此
才在那里留下一份副本。

顶层软件包本身(不包括它的传递依赖,那些依赖仍然只是缓存目录的一个实现细节)同时也会被登记到下文
描述的**软件包**管理员缓存中,这样 nspawnmgr 为自己的配置过程所抓取的内容,在那里同样是可见和可复用
的,而不只是某次容器创建过程中一个隐藏的副作用。

### 上传并安装任意软件包

管理员也可以直接上传任何软件包文件:**软件包**页面(从容器列表进入,仅限管理员)接受一个
`.deb`/`.rpm`/任何你的包管理器所使用格式的文件,加上一个可选的描述。随后每个容器所有者都会在自己
容器的详情页面上看到一个对应的**安装软件包**区域(只提供该容器自身包管理器对应的软件包)——选择一个
并点击"安装",会先把它复制到容器上,然后,对于**APT、DNF 和 PACMAN**软件包,先*模拟*执行安装
(`apt-get install -s` / `dnf install --assumeno` / `pacman -U --print`,不做任何实际改动),
针对容器自身的状态,找出它尚未拥有的任何依赖。任何缺失的依赖都会按照 SSH/RDP/VNC/桌面管理器配置流程
已经采用的方式被抓取(参见上文——APT 是主机端,DNF/PACMAN 通过 `systemd-run --machine=` 在容器自身
内部完成,因为两者都不会出现在这台主机自身的 `PATH` 上),并同样登记进软件包缓存中,然后真正的安装
会通过包管理器自身的本地文件安装命令来运行(`apt-get install <path>` / `dnf install <path>` /
`pacman -U --noconfirm <path>`)——它自身的依赖解析会在一次连贯的过程中,同时处理上传的文件和刚刚被
预抓取的内容。DNF/PACMAN 自身的本地安装通常会直接从容器自身的网络访问中解析依赖,与它们对任何具名
软件包的处理方式相同——预抓取步骤仍然会照常运行,这是刻意为之的,目的是与 APT"绝不让容器直接访问
网络进行实时包管理器镜像源查询"这一姿态保持一致(DNF/PACMAN 自身的预抓取仍然需要容器的网络来完成
容器内的下载本身——它只是把这种需求限制在一个单一的、仅下载、非交互式的步骤里,而不是真正的安装
命令)。这个子步骤需要与容器创建相同等级的 sudo 密码,所以如果没有配置存储的 sudo 密钥、且请求本身
也没有提供一个,它会直接失败(不会出现无声的部分安装)。**DNF 和 PACMAN 对于把一个已上传的软件包安装
*进 Fedora/Arch 容器内部*的支持尚未经过验证**——这与安装*nspawnmgr 自身*到一台真实的 RPM/Arch 主机
上不同,后者已经过验证(参见上文的 RPM 和 Arch 软件包安装章节);这个特定的容器内软件包上传流程从未
针对真实的 Fedora/Arch 容器实际执行过,只是尽可能严格地按照每个工具文档记录的 CLI 约定构建的——如果
发现任何实际差异,请反馈。**PACMAN 是两者中更具推测性的一个**:与
`apt-get install -s`/`dnf install --assumeno`(它们是 apt/dnf 自身文档完善的试运行模式)不同,
`pacman -U --print` 针对一次完整的本地文件依赖闭包模拟的行为,在本项目中从未被实际执行过,连手动
测试都没有。**APK**软件包会跳过所有这些流程,只运行一次简单的本地安装(`apk add <path>`),不做任何
依赖解析——如果缺少依赖,输出中仍然会出现一个可见的错误,不会被自动修复(APK 自身的本地安装实际上是
会从已配置的仓库中解析依赖的,但无论如何,基于 Alpine 的容器在这个应用中目前并不能完全正常工作——
见下文)。nspawnmgr 自动下载的软件包(无论是为了自己的 SSH/RDP/VNC/桌面管理器配置,还是作为这个流程
抓取的一个依赖)也会出现在这里,归属于第一个抓取它们的容器的创建过程或安装操作,与管理员手动上传的
内容并列显示。

软件包页面上的**"显示传递依赖"**按钮填补了这里刻意留下的空白:选择一个包管理器
(APT/DNF/PACMAN,即拥有预抓取缓存目录的这三个),它就会列出该包管理器共享的
`/var/cache/nspawnmgr/packages/<manager>/auto` 目录中实际存在的每一个文件,并附上以字节为单位的
大小。这个列表是每次点击该按钮时,通过 shell 调用并实时读取真实目录动态生成的
(`nspawnmgr-list-auto-cache.sh`,一个 NOPASSWD 的只读封装脚本)——与上面表格中的顶层软件包不同,
这里的内容完全不会存储在数据库中。这对于确认某个依赖是否确实已经落地到磁盘上,或者粗略估算某个包
管理器随时间积累了多少共享缓存目录内容,都很有用。

### 可移动介质(ISO 镜像)

**ISO** 是一个真实的 `PackageManager` 取值,不是一个单独的缓存/实体/管理页面——从同一个**软件包**
管理页面上传一个 ISO,就像上传 `.deb`/`.rpm` 一样,只是选择 `ISO` 而不是 `APT`/`DNF`/`APK`/
`PACMAN`。`.deb`/`.rpm` 那一套安装机制不适用于它(`ISO` 没有对应的安装命令,并且
`Template.packageManager` 永远不能是 `ISO`——模板管理表单自身的下拉菜单已将其排除),但上传/缓存/
CI 发布路径与其他类型是完全相同的,这是刻意选择复用同一套流程,而不是再构建一套并行的。之后任何容器
所有者都可以从自己容器的详情页面的"可移动介质"区域配置一个已上传的 ISO——就像一台真正的光驱一样,
每次每个容器最多一个,并且始终以只读方式挂载在固定的 `/mnt/cdrom`。在已经配置了一个 ISO 的情况下
挂载另一个,会自动先弹出旧的那个;没有单独的"先弹出再挂载"这一步。

**这是一个持久化的、声明式的设置——正如[自定义端口映射](#自定义端口映射与出站访问),而不是一个
实时操作。**挂载/弹出会立即重写容器的 `.nspawn` 文件(一行静态的 `[Files]` `BindReadOnly=`),但只有
在容器下一次(重新)启动时才会生效,并且会一直保持这个配置,直到被显式更改或弹出为止——设置它并不
要求容器正在运行,停止/重启也*不会*清除它。主机端的部分(一个通过循环设备挂载在固定的每容器路径上的
ISO 文件,`nspawnmgr-mount-iso.sh`/`nspawnmgr-unmount-iso.sh`)会在你挂载/弹出的那一刻被设置/拆除,
与容器当时是否正在运行无关;但是,主机重启目前不会自动重新建立那个循环挂载,所以一台在主机重启后启动
的、仍然配置着某个 ISO 的容器会启动失败,直到手动处理为止
(`mount -o loop,ro <iso> /var/lib/nspawnmgr/iso-mounts/<name>`)——这是一个已知的限制,目前不会被
自动协调解决。

**这使得 `systemd-networkd` 成为一个硬性前提条件,而不仅仅是出站访问的一个锦上添花的功能**——
nspawnmgr 自身的 postinst 用它来创建和配置 `nspawnbr0` 本身(见上文),而 nspawnmgr 的就绪检查和
`guacd` 一旦容器有了 `host0` 地址,都会直接拨号连接它,所以一个永远得不到该地址的容器(模板中从未
启用 `host0`——见下面第 2 步)永远不会离开 `BOOTING` 状态,彻底卡住,而不仅仅是缓慢。如果容器无法
到达 `RUNNING` 状态,请检查你自己的模板中是否有 `systemctl enable systemd-networkd`。

唯一剩下的主机级入站转发是[自定义端口映射](#自定义端口映射与出站访问)——完全可选、由所有者自行管理,
使用相同的 `Port=tcp:<host-port>:<container-port>` `.nspawn` 机制(`systemd-nspawn` 仍然会在启动时
自动把它设置为 DNAT 规则)。

具体来说,要完成这部分设置:

1. `sudo systemctl enable --now systemd-networkd`(**网络诊断**中有一项检查以及一键修复功能),以及
   `sudo sysctl -w net.ipv4.ip_forward=1`(在 `/etc/sysctl.d/` 下将其持久化)——`nspawnbr0` 自身
   `.network` 文件中的 `IPMasquerade=yes`(见上文)会添加 NAT 规则,但接口之间实际的数据包转发是一个
   独立的、全内核范围的设置,这个软件包不会替你打开它。如果 NetworkManager/ifupdown 已经在管理你的
   主网卡,告诉它不要管 `nspawnbr0`(例如 NetworkManager.conf 中的
   `unmanaged-devices=interface-name:nspawnbr0`),这样 networkd 就可以自由管理它。
2. 在容器**模板**内部,在构建之前(与[第2节](#容器模板基础根文件系统)中构建 `openssh-server` 的
   同一步):`systemctl enable systemd-networkd`,这样 `host0` 才能真正从网桥获取它的 DHCP
   配置——`debootstrap` 的输出默认不会启用它。这是**必需的**,不是可选的:跳过这一步,由该模板创建的
   容器将永远无法离开 `BOOTING` 状态。
3. 启动(或重启)一个容器——`machinectl start` 会把它的 veth 纳入 `nspawnbr0`,它会通过 DHCP 从网桥
   获得一个地址和路由,此后 nspawnmgr/`guacd` 就可以直接连接它了。

### 按名称解析容器

受管容器之间已经可以通过 IP 相互连接了(nspawnmgr 自身的防火墙设置中没有任何东西会阻止容器到容器的
`FORWARD` 流量——`NSPAWNMGR-OUTBOUND` 链上的 DROP 规则只匹配某个容器*自身*发出的出站数据包,与目的地
无关)。本节要解决的问题,是提供一种按名称而不是按内部地址来查找对方的方式,因为内部地址是按容器由
DHCP 分配的,并且可能在重启之间发生变化。

`dnsmasq` 是本软件包真实的 `apt` 依赖项(与打包在一起的 guacd/Tomcat 不同——参见[第2节](#2-主机先决条件);
`dnsmasq` 的 hosts 文件服务行为在各版本间足够简单和稳定,不需要固定某个版本)。它会被自动安装并配置:
只绑定在 `nspawnbr0` 上(永远无法从主机自身的局域网/上行接口访问到——它不是、也绝不能成为一个开放
解析器),提供 `/etc/nspawnmgr/dns-hosts` 中的内容。每个容器也会自动获得 `nspawnbr0` 自身的地址
(`10.100.0.1`)作为它的 DNS 服务器,直接来自 `nspawnbr0` 的 `.network` 文件——不需要任何额外的管理员
步骤。nspawnmgr 会重新生成 `/etc/nspawnmgr/dns-hosts`(`ContainerDnsSyncService`,每约 15 秒一次),
内容来自每个当前处于 `RUNNING` 状态的 MANAGED 容器自身的名称和内部地址——与 `guacd`/就绪检查已经
解析过的地址相同(见上文),所以没有需要额外发现的新内容。dnsmasq 不会自行注意到 `addn-hosts` 文件的
变化(它没有自动的/基于 inotify 的重新加载机制,只支持 SIGHUP 或重启),所以每次写入之后都会紧跟一次
重新加载(`nspawnmgr-reload-dnsmasq.sh`/`DnsReloader`)——没有它,不论磁盘上的文件内容有多新,
容器之间都会一直无法相互解析。

由于这个 `dnsmasq` 实例直接运行在主机上,默认情况下它也会读取并向容器提供主机自身的 `/etc/hosts`
内容(已在实际环境中确认这是期望的行为)——管理员自己在那里添加的静态局域网条目(例如
`192.168.1.15 acer`)也会变得可以从每个容器内部解析,而不仅仅是从主机自身。唯一需要注意的地方是:
如果 `/etc/hosts` 也把主机自身的裸主机名映射到一个回环地址(Debian 自身的
`127.0.1.1 <hostname>` 约定),*并且*同一个裸名称又被设置为下面的外部主机名设置,这两个来源就会发生
冲突,dnsmasq 可能会以任意一个地址作答——请避免为该设置选择一个已经在 `/etc/hosts` 中被映射的短名称。

`/etc/nspawnmgr/dns-hosts` 还携带另一条固定条目:主机自身的外部主机名
(`nspawnmgr.host.external-hostname`/`HOST_EXTERNAL_HOSTNAME`——在安装时由 `setup-sudo-account.sh`
自动检测,之后可以在[`/admin/settings`](#实时可编辑设置adminsettings)实时编辑),指向
`nspawnbr0` 自身的固定地址(`10.100.0.1`)。一个容器完全没有其他途径回连主机——正是这条记录,让容器
能够解析主机自身的名称,从而访问主机转发回来的任何内容(例如一个[自定义端口映射](#自定义端口映射与出站访问))。
它按照与上面的容器条目相同的方式、相同的周期保持同步;当它仍然处于未配置的 `localhost` 默认值时,
会被完全省略(把"localhost"本身映射到 `10.100.0.1` 是明显错误的,而不仅仅是没有帮助)。

同一个 `dnsmasq` 实例也是每个容器*唯一*的 DNS 服务器——不仅仅针对 `.internal` 名称——所以它也会把
`.internal` 之外的任何查询转发给已配置的上游解析器,即 `nspawnmgr.dns.upstream-servers`(默认
`1.1.1.1,9.9.9.9`),可以在[`/admin/settings`](#实时可编辑设置adminsettings)实时编辑——例如可以
把它指向企业内部的 DNS 服务器。如果没有配置任何上游服务器,容器自身的
`dnf`/`pacman`/`apt`(从它们真实的软件包镜像源抓取)或任何需要解析真实互联网主机名的操作都会直接以
"Could not resolve host"失败——已在实际环境中确认。这仍然不是上面所说的那种开放解析器:转发是经由
主机自身正常的互联网路由完成的,并且 `dnsmasq` 本身仍然只绑定在 `nspawnbr0` 上,从容器网桥之外是
无法访问的。

上游服务器保存在它们自己的文件 `/etc/dnsmasq.d/nspawnmgr-upstream.conf` 中——与上面的主配置文件
`nspawnmgr.conf` 是分开的——通过 dnsmasq 自身的 `conf-dir=/etc/dnsmasq.d/`(Debian 默认的
`/etc/dnsmasq.conf` 中已包含)自动一并加载,不需要额外的指令。`ContainerDnsSyncService` 会按照与
保持 `dns-hosts` 与正在运行的容器同步相同的方式(每约 15 秒轮询一次,只有当有效值真正发生变化时才会
重写)来保持这个文件与当前设置同步。`postinst` 在首次安装时(仅当该文件尚不存在时)会用同样的
`1.1.1.1`/`9.9.9.9` 默认值为它做初始化,这样即便是在 nspawnmgr 自身还没有启动、接手同步职责之前,
上游解析从第一次启动开始就能正常工作。

容器之间可以通过它们在 nspawnmgr 中的裸名称(`b1`),或者通过固定的 `.internal` 后缀下的 FQDN
(`b1.internal`)相互解析——dnsmasq 的 `domain=`/`expand-hosts` 选项会从同一批 `dns-hosts` 条目
自动同时提供这两种形式,不需要单独配置。`internal` 是 IANA 专门为此保留的特殊用途顶级域(RFC 8375,
与 `home.arpa` 属于同一类别),不是一个自造的域名,所以可以保证永远不会与一个真实的公网域名冲突。
适用范围仅限于 MANAGED 容器(EXTERNAL、由管理员配置的 Host 已经拥有自己的 `hostname`,不会被加入
这里),而且这个命名空间在所有容器之间是扁平的——这纯粹是网络层面的可达性,与某个具体用户在 web 界面
中能看到或连接哪些容器无关(机器网格只会显示用户自己拥有或被共享的机器,管理员除外,管理员无论所有权
如何都能看到全部内容)。

要让这一切端到端地正常工作,还需要另外两个环节:

- **容器一侧**:`systemd-resolved` 拒绝把一个未限定(不带点)的名称,例如 `b2`,发送给一台真实的
  DNS 服务器——除非是发给 LLMNR/mDNS——除非该链路已经配置了一个路由/搜索域来对它进行限定。DHCP 本可以
  提供这一点,但那需要容器自身的 `80-container-host0.network`(由 `systemd-nspawn` 自身生成,不是
  这个模板可以控制的内容)主动选择加入 `UseDomains=yes`,而它默认并不会这样做。这个模板转而在
  `/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf` 提供了一个静态的 drop-in
  (`[Network]\nDomains=internal`),以与 systemd 单元 drop-in 相同的方式按文件名合并——完全绕开了
  DHCP,也不依赖任何选项真的被发送过来。
- **dnsmasq 一侧**:`domain=`/`expand-hosts` 本身只控制 dnsmasq*为自己的应答添加*的后缀——它们并不会
  让 dnsmasq 对一个已经*带着*限定信息到达的查询(正是上面那个带有路由域的容器现在发送的查询)具有
  权威性。如果不同时设置 `local=/internal/`,一个到来的 `b2.internal` 查询会完全绕过 hosts/
  `addn-hosts` 匹配,像任何其他名称一样被转发到上游——而 `.internal` 在公网上并不存在,所以那样只会
  失败(而且还会把容器名称泄露给配置的公共解析器)。`local=/internal/` 把 `.internal` 标记为
  dnsmasq 自己的权威区域:只从自己的 hosts 数据中作答,对任何真正未知的名称返回 `NXDOMAIN`,从不转发。

如果你曾经直接在一台正在运行的主机上手动编辑过任意一个 dnsmasq 文件:`domain=`、`expand-hosts`、
`local=`(在 `nspawnmgr.conf` 中)以及 `server=`(在 `nspawnmgr-upstream.conf` 中)全都是结构性的——
dnsmasq 只会在进程启动时解析它们,这一点已在实际环境中确认——与 `addn-hosts` 不同,后者会被
`DnsReloader.reload()`/`nspawnmgr-reload-dnsmasq.sh` 通过 `SIGHUP` 正确地热重载。手动编辑任何一个
结构性配置项之后,单纯执行 `systemctl reload dnsmasq` 是没有效果的;请使用
`systemctl restart dnsmasq`。`ContainerDnsSyncService` 已经知道这个区别:一次 `addn-hosts` 的变更
会像上面那样通过 `DnsReloader.reload()`(SIGHUP)处理,但一次上游服务器的变更则会改为通过独立的
`DnsReloader.restart()`/`nspawnmgr-restart-dnsmasq.sh`(一次完整的 `systemctl restart`)处理——
如果对后者也使用 `reload()`,会导致磁盘上的文件内容是正确的,但 dnsmasq 仍然在悄悄地继续用它最后一次
真正启动时的内容作答。一次正常的软件包安装/升级两者都不需要:`.deb` 的 postinst 在(重新)安装
`nspawnmgr.conf` 时总是会执行自己的一次完整 `restart`。

### 发现在 nspawnmgr 之外创建的机器

如果一台机器是直接在主机上手动创建的——你自己运行的 `machinectl clone`/`debootstrap`/
`import-tar`,或者从备份中还原的镜像——在管理员点击容器列表上的**发现机器**之前,nspawnmgr 完全不知道
它的存在。这会把 `machinectl` 当前已知的每一个镜像名称与 nspawnmgr 自己的数据库进行比对,并把所有
尚未被跟踪的都注册为一个普通的 MANAGED 容器,**归属于运行这次发现操作的那个管理员**。重复运行是安全
的——任何已经被跟踪的(按名称匹配)都会被跳过。

发现操作会登记该机器的存在,让你可以启动/停止/删除它,并按名称解析它(见上文)。它刻意从不会像通过
nspawnmgr 创建容器那样安装一个 SSH/RDP/VNC 管理员账户——与 nspawnmgr 自己配置的容器不同,对于一个
手工构建的镜像内部已经存在什么,是没有办法知道的,所以它从不会假设某个账户名,也不会为这三者中的
任何一个运行 `useradd`/安装服务器。它*确实*会做的是:在注册每台机器之后,立即检查 SSH(22 端口)、
RDP(3389 端口)或 VNC(5900 端口)是否已经在监听,如果是,就自动为它接好一个 Guacamole 连接——采用
**提示凭据**模式,与下面 Host 页面使用的机制相同,所以每次连接时都会要求你输入用户名/密码,而不是由
nspawnmgr 生成并存储一个。如果在发现时这三个端口都还没有开放(或者你之后在那台机器上启用了其中一个),
请改为从该容器自己的详情页面手动完成——参见下文的"远程访问"。

### 未由 nspawnmgr 自身配置的容器的远程访问

只要 nspawnmgr 对某个协议没有为该容器生成凭据,该容器的详情页面上就会针对 SSH、RDP、VNC 分别显示一个
**远程访问**区域——对于一台被发现的容器,这总是成立的;对于一台由 nspawnmgr 创建的普通容器,如果在
创建时拒绝了 RDP/VNC,这一点同样成立。点击**启用 SSH/RDP/VNC 访问**会检查该端口此刻是否确实在监听,
只有在确实如此的情况下,才会接好一个提示凭据式的 Guacamole 连接,与发现操作自身的自动接线步骤完全
一样;**禁用**则会将其再次移除。这项检查只在你点击"启用"的那一刻发生一次——如果容器内部的服务之后又
停止了,连接按钮会一直保持可用状态,直到下一次连接尝试失败为止,而不是让 nspawnmgr 在后台持续不断地
重新探测每一个容器。

对于一个 nspawnmgr 已经用真实生成的凭据在管理的协议(每个容器的 SSH,以及在创建时请求过的
RDP),这个区域是刻意从不提供的——那个连接会被完全保留不动,这样这个功能就永远不会悄悄地用一个提示
凭据式连接取代一个正常工作的、已生成的凭据。

### Host:由管理员管理的外部机器

一个**Host**是网络上任意一台机器的条目,它完全不是一个由 nspawnmgr 管理的容器——一台已有的 Windows
机器、一个 NAS、另一个团队的服务器,任何可以通过 SSH/RDP/VNC 访问、并且适合通过与其他一切相同的
Guacamole 单点登录流程来访问的东西。这里没有单独的 Host 页面:一个 Host 底层就是一个 `Container` 行
(种类为 `EXTERNAL`),所以它作为一张普通卡片显示——带有一个固定的 `HOST` 徽标,而不是某个后端徽标——
就在主**机器**网格上,与 nspawn/podman/QEMU 机器并排显示,它的详情页面使用与所有其他机器相同的
`/containers/{id}` 路由。管理员通过"+"菜单的**新建 Host**项(`/admin/hosts/new`,仅限管理员)添加一个
条目:名称、主机名/IP、一个所有者用户名(必须属于一个至少已经登录过一次的用户)、以及要提供
SSH/RDP/VNC 中的哪些协议、各自使用哪个端口。管理员在查看某个 Host 自己的详情页面时,会在其"管理"面板
中看到**编辑 Host**(回到同一个表单,位于 `/admin/hosts/{id}/edit`)和**删除 Host**按钮——不存在
一个单独的 Host 列表页面;数据库是唯一的真相来源。

**可见性遵循与其他任何机器相同的所有者/管理员/共享规则**——一个 Host 不会因为是由管理员创建的就变成
公开可见;只有管理员、它的所有者,或者被明确共享过的人,才能在自己的机器网格中看到它
(`ContainerRepository.findVisibleToUserOrderByName` 对 nspawn、podman、QEMU 和 Host 行统一应用
这一规则)。

**RUNNING/STOPPED 是实时解析的,不是存储的状态。**由于 nspawnmgr 完全不控制一个 Host 的生命周期,
它的状态徽标来自一次单纯的 TCP 可达性检查(`HostLivenessService`),针对它已配置并启用的
SSH/RDP/VNC 端口中的一个——如果存在则优先 SSH,其次 RDP,再次 VNC——每个 Host 缓存一分钟,这样机器
网格和该 Host 自己的详情页面就不会在每次请求时都各自触发一次全新的探测。一个三者都未启用的 Host
没有什么可探测的,总是显示为 RUNNING。

连接总是实时提示输入凭据——nspawnmgr 从不为一个 Host 存储密码,这与发现操作自身的自动接线以及上面
的单容器远程访问区域所使用的提示凭据机制是同一套。

主机名/IP 字段可以是一个真实的主机名,不仅仅是一个地址——在一次自托管安装中,Guacamole 自身的
SSH/RDP/VNC 客户端运行在自托管的 `nspawnmgr` 容器内部,它唯一的 DNS 路径就是 nspawnmgr 自己的
dnsmasq(容器名称加上公共上游解析器),对一个私有局域网自身的名称解析完全没有可见性。为了绕开这个
限制,nspawnmgr 会在每次有人发起连接时,通过与其他所有特权操作相同的、具备 sudo 权限的 SSH 账户,
在底层主机上重新解析这个主机名,并把解析出来的地址直接交给 Guacamole,而不是主机名本身——这样一个
只有你的网络自己的 DNS/NetBIOS/mDNS 才知道的、仅限局域网内使用的名称仍然可以正常工作,一个由 DHCP
重新分配的地址也能在下次连接时被自动获取,而不需要管理员注意到并重新保存这条记录。如果这个主机名在
连接时刻在主机上无法解析,连接尝试会以一个明确的错误直接失败,而不是继续使用一个过期的地址。

共享的工作方式与容器完全相同:所有者从这条记录自己的详情页面上管理谁还可以连接。一个不是所有者的
管理员会在那里的"管理"面板下看到一个**取得所有权**按钮——这对于接管一个所有者已经离开的 Host(或
任何机器)很有用,不需要数据库访问权限。

机器网格和 Host 自己的详情页面上的 SSH/RDP/VNC 按钮,都会在一个新的浏览器标签页中打开 Guacamole 会话,
而不是离开当前页面——当需要从同一个页面连接多台机器时很有用。从一个 Host 卡片打开会话使用的是
`/hosts/{name}/session/{protocol}`,这是它自己独立的 URL 命名空间,与普通机器的
`/containers/{name}/session/{protocol}` 不同——如上所述,一个 Host 底层是一个 Container 行,但从
管理员的角度看,一个用户在浏览器中实际看到的*会话* URL 刻意不使用"containers"这个词,因为它本质上不
是一个容器。两条路由渲染的是完全相同的模板/JS(一个 iframe 加上对同一个
`/api/containers/{id}/session/{protocol}` API 端点的一次 fetch 调用);只有页面 URL 不同。两者都是
按机器的**名称**、而不是它的数字 id 来定位的——这是刻意的选择,这样一个分享出去的链接或浏览器历史
记录中的 URL 才能保持有意义。

### 自定义端口映射与出站访问

除了上面的 SSH/RDP 之外,一个容器的**所有者**还可以从其详情页面上自助完成另外两件事——两者都不需要
管理员操作:

- **自定义入站端口映射**:任何额外的 TCP 或 UDP 主机端口 → 容器端口转发,由所有者自行选择两个端口
  号。nspawnmgr 会在接受之前检查所请求的主机端口是否已经被另一个自定义映射占用。一条映射会被立即写入
  `.nspawn` 文件,但只有在容器下一次(重新)启动时才会生效——为一个正在运行的容器添加一条映射会显示
  一条"需要重启"的提示,而不是自动重启它。
- **出站互联网访问开关**:与上文那种主机范围的、要么全开要么全关的伪装设置不同,每个容器可以单独将
  其出站访问阻断。nspawnmgr 通过一个专用的 `NSPAWNMGR-OUTBOUND` iptables 链自行管理这一点
  (首次需要时自动创建,从 `FORWARD` 的顶部跳转过来),其中每个被禁用出站访问的容器对应一条 `DROP`
  规则,以该容器实际的主机端 veth 接口为键——nspawnmgr 每次都会动态查找这个接口(通过该 veth 的对端
  ifindex),因为如上所述,veth 名称并不是一个可以从容器名称直接推导出来的可预测字符串。对一个正在
  运行的容器切换这个开关会立即生效,不需要重启。
- **出站白名单**:即使出站访问已被禁用,所有者仍然可以为特定目的地打开一个通道——一个字面的 IPv4
  地址、端口和协议(TCP/UDP)——例如 `127.0.0.1`,这样容器就可以访问另一个同主机上的容器/服务,而
  不必授予它一般的互联网访问权限。实现方式是在同一个 `NSPAWNMGR-OUTBOUND` 链中、该容器 DROP 规则之前
  插入 ACCEPT 规则;每次变更都会把该容器的规则完全清空并从头重建,而不是就地修改。在出站访问已启用
  时这个设置没有任何效果——那种情况下一切本来就已经是可达的。同样会立即生效,不需要重启。

两者都需要 `iptables` 命令可用,并且可以通过[第3节](#3-具备-sudo-权限的-ssh-账户)中那个具备 sudo
权限的账户免密码使用——与 nspawnmgr 已经用来写入 `.nspawn` 文件、启动/停止容器的账户和机制完全相同。

## 3. 具备 sudo 权限的 SSH 账户

在同一台主机上创建一个专用的本地账户,赋予受限范围的 sudo 权限,nspawnmgr 会通过 SSH 连接到这个账户
(始终通过回环地址 `127.0.0.1`),用它来真正运行 `machinectl`/`systemd-run`,以及触碰
root 拥有的路径。**推荐做法:**让 `packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh` 替你完成
这一切——这正是 `.deb` 的 `postinst` 所运行的那个脚本,但它完全可以独立运行,不需要构建或安装这个
软件包本身:

```bash
sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
```

从本仓库的一份检出运行(不需要任何参数——它会自动检测自己旁边的 `privileged-scripts/` 和
`debian/nspawnmgr.sudoers`),它会创建 `nspawnmgr_exec` 系统账户、生成并存储一个随机密码、生成一个
SSH 密钥对、把下文提到的封装脚本安装到 `/usr/lib/nspawnmgr/privileged/`、安装并校验 sudoers 授权,
并且如果你的主机全局禁用了它,还会为该账户单独开一个 sshd `PasswordAuthentication` 例外。它是幂等的
——升级后重新运行,或者用来获取更新后的封装脚本,都是安全的。完整细节请参见该脚本自身的头部注释。

如果你更想完全手动完成这一切(例如想使用一个不同的账户名),可以把这个脚本自身的做法当作参考——但请
注意下面的两个权限等级,因为一个笼统的 `usermod -aG sudo`(任意命令,始终需要密码)已经不再匹配
nspawnmgr 实际调用这个账户的方式了。

### 两个权限等级

这个账户的 sudoers 访问权限被拆分为两个等级,而不是一个:

- **NOPASSWD**——形状固定、始终安全的命令:`machinectl start/poweroff/terminate/reboot/remove/show`、
  `systemd-run --machine=... --pipe --quiet --wait /bin/sh -s`(运行一个存储的容器脚本——具体为什么
  这一个特定形状的 `systemd-run` 是 NOPASSWD,而下面那个通用形式不是,参见下文的"信任边界:容器
  脚本"),以及 `/usr/lib/nspawnmgr/privileged/` 下负责写入 `.nspawn` 设置、删除容器文件、以及出站
  防火墙同步的封装脚本。这些都是常规的、由所有者触发的操作(启动一个容器、编辑它的端口映射、删除它、
  运行自己编写的脚本),无论下面哪种容器创建模式处于激活状态,都绝不应该阻塞等待管理员介入。
- **需要密码**(没有 `NOPASSWD` 标记)——`systemd-run --machine=... --pipe --quiet --wait`
  (在一个全新容器内部以 root 身份运行任意的、模板作者编写的内容——参见下文的"信任边界")、
  `nspawnmgr-clone-template.sh` 封装脚本,以及 `nspawnmgr-create-debian-template.sh` 封装脚本
  (下载/解压一个真实的 Debian 根文件系统——参见第2节的"容器模板",以及模板管理页面的"设置
  debian-minimal"按钮)。这三者都仅在创建时才会用到——前两个由 `ProvisioningService` 针对每个容器
  精确调用一次,第三个只会在管理员需要、且当前不存在任何模板时按需调用。具体使用哪个密码——以及在
  没有管理员介入的情况下是否有密码可用——取决于下面的模式。

每一条特权命令都要经过这两种固定参数的封装脚本或 `machinectl`/`systemd-run` 调用方式之一——
nspawnmgr 从不要求 sudo 运行一段任意的内联脚本,正是为了让上面的 sudoers 授权可以精确匹配一条确切的
命令/路径,而不必对脚本文本做通配符匹配(那样会很脆弱:未来对脚本内容的任何改动都会悄悄地使这条
授权失效——或者悄悄地把授权范围放得过宽)。

### 容器创建模式:存储密钥 vs. 管理员审批

创建容器究竟是完全自助式的,还是需要管理员签字批准,取决于
`nspawnmgr.ssh.password`/`SSH_PASSWORD` 是否已经配置——这是**推导**出来的结果,没有一个单独的开关:

- **存储密钥/自助服务模式**(已配置密码,这是 `.deb` 的默认设置):所有者的"创建容器"请求会立即、
  自动地开始配置,与这项功能存在之前的行为一致。
- **管理员审批模式**(密码留空):一个新容器会进入 `PENDING_APPROVAL` 状态,而不是立即开始配置。
  **Requests** 页面(`/requests`——它的侧边栏导航项只有在这种模式激活时才会出现,对任何人都是如此)
  会把它与任何待处理的容器内用户账户请求汇总在同一个视图中列出。管理员可以看到并处理来自每个用户的
  每一个待处理项目;一个非管理员用户只能看到自己的项目,并且可以**拒绝**它们(转为终态
  `DENIED`,从不会尝试 SSH)但不能**批准**——批准需要一个 sudo 密码,以内联方式提供,仅用于那一个
  条目创建阶段的步骤,保存在内存中,该次运行完成后即被清零,从不会被持久化——刻意设计为只向管理员
  索取。

SSH 传输登录和 sudo 密码共用同一个已配置的值,所以把 `SSH_PASSWORD` 留空以选择管理员审批模式,原本
会导致 SSH 会话本身没有任何东西可以用来认证——甚至连上面的 NOPASSWD 等级都不行。**因此管理员审批模式
要求必须设置 `nspawnmgr.ssh.private-key-path`/`SSH_PRIVATE_KEY_PATH`**,这样 SSH 传输认证就会改用
密钥,而不是那个(现在为空的)密码。`setup-sudo-account.sh` 无论处于哪种模式,都会无条件生成这个
密钥,所以之后切换模式真的只是把一个环境变量置空/设置好并重启——不需要额外的设置。如果既没有配置
密码也没有配置私钥,nspawnmgr 会直接启动失败(`SshPropertiesValidator`),而不是等到第一次容器操作时
才把这个问题表现为一个令人困惑的连接失败。

### 管理员/用户角色

需要一个用户的角色(`USER`/`ADMIN`)来控制上面审批页面的访问权限。同样有两种模式,同样是由一个配置
值是否设置来决定的——这次是 `nspawnmgr.auth.user-is-admin-json`:

- **应用管理**(默认,留空):**第一个登录的用户**会被自动提升为 `ADMIN`;其他所有人默认为
  `USER`。此后,任何管理员都可以在 `/admin/users` 提升或降级任何其他用户。角色是持久固定的——从不会
  在登录时被悄悄重新计算。
- **外部管理**(`nspawnmgr.auth.user-is-admin-json` 被设置为指向 `auth.war` 已经返回的那份身份 JSON
  中某个字段的 JsonPath,与 `user-id-json`/`user-username-json` 等并列):角色会在每次登录时从那份
  JSON 重新计算——提升和降级都是如此——手动授权/撤销页面会完全拒绝任何变更,因为在这种模式下,外部
  身份来源才是权威的。

### 信任边界:模板作者编写的配置命令

上面需要密码的等级允许 `systemd-run` 在容器内部以 root 身份执行内容。那些内容永远来自以下两处之一:
`ProvisioningService` 自身的一个字面字符串,或者
`Template.installSshCommand`/`installXrdpCommand`。模板可以通过 `/admin/templates` 编辑,受限于
`/admin/**` 已有的 ADMIN 角色权限,而不是一个单独的审批流程。换句话说:**任何拥有 ADMIN 角色的人,
实际上都控制着由他们编辑的模板所创建的每一个容器内部、以 root 身份运行的内容。**在应用管理的角色
模式下,任何当前的管理员都可以在 `/admin/users` 自助地把 ADMIN 权限授予任何其他人,不需要额外的
审批步骤。普通(非管理员)的已登录用户完全无法触及这些内容——只有 `GET /api/templates`(仅限已激活
模板的概要信息)会暴露在 `/admin/**` 之外。

### 信任边界:容器脚本

一个容器的所有者(或该容器已经共享给的任何人——参见容器详情页面上的"已共享给")可以定义具名脚本,
并通过 `/containers/{id}/scripts` 在那个容器内部以 root 身份运行它们。这与上面的模板编辑是一种不同
的信任形态:作者是容器自身的所有者/被共享用户,并且脚本永远只会在**那一个容器**内部运行,不会影响
任何其他人的容器。这些用户已经可以通过自己的 Guacamole SSH 会话,对那个确切的容器拥有完整的交互式
root shell 访问权限——通过这个功能运行一个已保存的脚本,并不会授予他们本来没有的任何权限;它纯粹是
一种便利(具名、可复用,一次点击而不用每次都通过 SSH 重新输入一遍)。这就是为什么运行一个脚本是
NOPASSWD 的(`/usr/bin/systemd-run --machine=* --pipe --quiet --wait /bin/sh -s`,形状固定,仅限
这一个确切的命令),与上面模板作者编写的内容不同——后者运行在*其他*人的容器内部,并且是由管理员而
不是容器自身的所有者编写的。

**"已共享给"授予的权限不仅仅是会话访问权。**共享一个容器,会同时授予对方一个 Guacamole SSH/RDP 会话
*以及*创建、编辑、删除、运行该容器脚本的能力(实质上等同于完整的 root 访问权限——见上文);没有一个
单独的开关可以只授予其中之一而不授予另一个。如果你出于纯粹的远程桌面便利性而与某些人共享过容器,
他们同样拥有脚本权限。

### 其他设置注意事项

- 这个账户还需要对你所指向的 `TEMPLATES_DIR` 拥有读写权限。
- 由于这个连接设计上仅限回环地址,nspawnmgr 默认对这条连接使用
  `strict-host-key-checking: false`。只有当你确实要把它指向一个非本地主机时才应该打开这个选项,并且
  要先确保 Tomcat 所用账户已经为目标主机填充好了 `~/.ssh/known_hosts`。
- **这一切都假设 nspawnmgr 管理的是它自己所运行的这台主机上的容器**(`.deb` 唯一支持的部署方式)。
  把 `nspawnmgr.ssh.host` 指向一台不同的主机,是一种手动配置的、工具链不支持的场景:你需要在那台
  远程主机上自行独立地重复本节所述的账户/sudoers/密钥对设置。
- **`nspawnmgr_exec` 的 SSH 访问设计上仅限回环地址**——不要把它的凭据交给这台主机之外的任何东西。
  如果你希望一个外部 CI/CD 流水线能够安装/更新容器模板,请改用单独的、刻意设计得更狭窄的
  `nspawnmgr_ci` 账户(参见上文的"从 CI/CD 流水线安装/更新模板")——它被隔离在自己的 sudoers 文件中,
  只有一条形状固定的授权,与 `nspawnmgr_exec` 那种广泛的 NOPASSWD/需密码访问权限不同,并且是设计为
  可以通过网络访问的。

你会把这个账户的用户名/密码(或私钥)填入 nspawnmgr 自己的配置中,作为 `nspawnmgr.ssh.*`(或
`SSH_USERNAME`/`SSH_PASSWORD`/`SSH_PRIVATE_KEY_PATH`),具体参见[第9节](#9-配置-nspawnmgr)。

## 4. 数据库

MySQL、MariaDB 或 PostgreSQL——没有 H2 选项。H2 仅在开发环境/CI 测试工具内部使用(一个内存数据库,
那个 JVM 一旦停止它就消失了);它从来都不是一个受支持的部署目标,现在也没有任何代码路径可以选择它
作为部署目标。MySQL 和 MariaDB 共用相同的 JDBC 驱动、schema 以及 Flyway 迁移位置——选择其中一个而
不是另一个,只会改变向导默认使用的机器名称(见下文),不会改变运行的代码路径。
`spring.datasource.url` 和 `spring.flyway.locations: classpath:db/migration/<vendor>` 必须保持
一致(参见环境变量参考中的 `DB_VENDOR`——始终是 `mysql` 或 `postgresql`,从不是
`mariadb`)。Flyway 会在启动时自动运行迁移;`spring.jpa.hibernate.ddl-auto` 是 `validate`,从不是
`update`——schema 完全由 Flyway 负责。

数据库同样是**自托管**的,与 nspawnmgr 自身一样([第1节](#1-架构概述))——下面的向导总是会为它
配置一台全新的 Debian 容器来运行,而不是要求你指向一台已有的服务器。

### 首次启动配置向导

在第一次启动 Tomcat 之前,你不需要自己准备任何数据库,或设置
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_VENDOR`——这个向导会替你完成。它存在于它自己的 WAR
(`ROOT.war`)中,部署在自托管的 `nspawnmgr` 机器内部 Tomcat 的根上下文
(`http://<host>:<forwarded port>/`,[第1节](#1-架构概述)),而不是在 `nspawnmgr.war` 自身内部:
一旦一个可用的数据库配置完成,访问 `/` 会直接把你重定向到 `/nspawnmgr/`,否则就显示这个向导。在数据库
尚未配置好之前直接访问 `/nspawnmgr/`,只会把你重新定向回 `/`——这个向导始终是唯一决定你当前处于哪种
状态的地方。

选择一个**数据库引擎**(MySQL、MariaDB 或 PostgreSQL),以及可选的、非默认的**数据库机器名称**——
按引擎默认为 `mysqldb`、`mariadb` 或 `postgresdb`,可编辑。同时填写一个**初始 nspawnmgr
用户名和密码**——一个真实的 Linux 账户,创建在自托管的 `nspawnmgr` 机器内部,一旦配置完成,你就会用
它登录(参见[第8节](#8-auth登录后端)了解为什么这就是 `auth.war` 的 PAM 后端所需要的一切,不需要
任何额外配置)。

提交之后,向导会:

1. 配置数据库机器(`nspawnmgr-bootstrap-db-machine.sh`,通过与这个应用中所有其他特权操作相同的、
   具备 sudo 权限的 SSH 账户运行,参见[第3节](#3-具备-sudo-权限的-ssh-账户))——克隆一个 Debian
   模板,安装选定的引擎(MySQL 和 MariaDB 都会安装 Debian 自身的 `mariadb-server`;Debian 上没有
   单独的 Oracle MySQL 软件包),并等待该机器内部一个首次启动的 systemd 单元,在引擎真正运行起来
   之后(不会尝试离线执行——两种引擎都确实需要短暂运行才能执行
   `CREATE DATABASE`/`CREATE USER`),创建既定的 `nspawnmgr`/`guacamole` 数据库和用户,并生成全新的
   密码。
2. 运行 nspawnmgr 自身的 Flyway 迁移,然后运行 Guacamole 的 schema 脚本(每次安装都总是从一个全新的
   数据库开始,所以这里不需要执行"schema 是否已经存在"的检查),并为你接好 Guacamole 的
   `guacamole-auth-jdbc` 扩展(把扩展 JAR 复制进 `GUACAMOLE_HOME/extensions/`,并把
   `<vendor>-hostname`/`-port`/`-database`/`-username`/`-password` 这些属性写入
   `GUACAMOLE_HOME/guacamole.properties`——参见[第7节](#7-guacamole)的"GUACAMOLE_HOME 与认证后端"
   了解这是做什么用的)。如果最后这一步因为某种原因失败了,不会造成致命影响——此时 nspawnmgr 自身的
   数据库(真正决定这个向导是否会一直显示的那个东西)已经在正常工作了,失败只会作为一条警告呈现出来,
   提示你手动完成这一步。
3. 通过同一个具备 sudo 权限的账户,回连进那台机器,在自托管的 `nspawnmgr` 机器内部创建初始的
   nspawnmgr Linux 账户——与 `ProvisioningService` 为一个普通受管容器创建登录账户所使用的机制完全
   相同。
4. 把可用的 nspawnmgr 连接设置保存到 `nspawnmgr` 机器内部的
   `/etc/nspawnmgr/db-config/db.properties`(归属 `tomcat:tomcat`,由
   `nspawnmgr-bootstrap-app-machine.sh` 自动创建)。

成功页面会立即原地重新加载 `nspawnmgr.war` 和 Guacamole 各自的上下文——不需要点击任何按钮,也不需要
重启 Tomcat——具体方式是触碰
`/opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml` 和 `guacamole.xml`(与其他特权操作使用的同一个
`nspawnmgr-write-file.sh` 封装脚本,通过向导自身那个不依赖 Spring 的 SSH 辅助工具运行,因为在启动
过程的这个阶段还没有应用上下文);Tomcat 自身的后台自动部署线程会注意到每一次变化,并原地重新部署
对应的上下文。对于 `/nspawnmgr`,这会重新运行它的启动可达性检查,这一次会真正启动应用本身。Guacamole
需要同样的处理:在一次全新启动时,它自己的 web 应用会在管理员有机会填写这个向导之前就启动(并在那
时读取一次 `guacamole.properties`/加载扩展)——如果不在这里同时重新部署它,Guacamole 就会一直在没有
加载数据库支持的认证扩展的情况下运行,拒绝所有登录,包括这个向导的 schema 步骤刚刚创建的
`guacadmin` 账户。这个页面会轮询 `/nspawnmgr/`,一旦它启动完成就自动带你过去——通常只需要几秒钟,
不再需要以前那样完整重启一次 Tomcat。

这个向导自身会把 `nspawnmgr` 机器和它的数据库机器都注册为普通的、可见的容器,加入 nspawnmgr 自己的
容器列表——归属于第 3 步中创建的账户,各自附带一条"虚拟机管理"/"数据库服务器"的描述——这是在迁移
完成后,直接在它自己的数据库操作中完成的,不需要先登录(同样的底层注册机制,参见
["发现在 nspawnmgr 之外创建的机器"](#发现在-nspawnmgr-之外创建的机器),否则需要管理员手动触发)。
当你第一次登录时(通过同一个账户),你只是被重新连接到这个向导已经创建好的管理员身份
([第3节](#管理员用户角色))——两台机器都已经在那里等着你了。之后它们不会被隐藏或特殊对待;你
可以像对待任何其他容器一样,SSH 进入其中任意一台、共享它、删除它——尽管删除你当前正在运行所依赖的
`nspawnmgr` 机器,显然不是一个好主意。

**这个向导表单本身是未经身份验证的,可以从任何主机访问。**因为还没有数据库,所以还没有用户表,所以
也就没有登录系统可供它藏身其后——任何能在数据库配置完成之前访问到这个端口的人,都可以完成这个配置。
如果这对你的部署很重要,请自行限制对这个端口的网络访问(防火墙规则,在完成第4节之前不要把它暴露在
公网接口上)。

## 5. 安装 nspawnmgr

从这里开始有两条路径——选一条。**选项 A(`.deb`)会替你完成第3节以及第6节的大部分内容**;选项 B 是
从第6节开始的完全手动流程。(Arch Linux 和 Fedora/RHEL 软件包也存在,自动化程度与选项 A 相同——参见
紧随其后的["在 Arch Linux 上安装"](#在-arch-linux-上安装)和
["在 Fedora/RHEL(RPM)上安装"](#在-fedorarhelrpm上安装)。)不论哪种方式,第4节(数据库)、第7节中的
Guacamole `GUACAMOLE_HOME`/JDBC 设置、第9节中的配置项,以及第10节的验证,仍然都是你自己的责任——
这三个软件包中没有一个自动化的内容超出了*sudo 账户*和*把 WAR 部署进 Tomcat*,都不涉及 Guacamole
自身的存储后端,或 nspawnmgr 应用层面的设置。

**要*构建*每种软件包格式所需要的东西,与要*安装*它所需要的东西并不相同**——在选择一条路径之前值得
了解这一点,尤其是当你构建所用的机器和实际部署的目标机器不是同一台时:

| 格式 | 构建需要 | 安装需要 | 是否可以跨平台构建? |
|---|---|---|---|
| `.deb`(`packaging/nspawnmgr-deb/`) | JDK 21 + Maven(`jdeb` 插件是纯 Java 实现) | `apt`,Debian/Ubuntu | **可以**——可以在任何拥有 JDK 的主机上构建,包括 Arch/Fedora/Windows/macOS |
| Arch(`packaging/nspawnmgr-arch/`) | JDK 21 + Maven,**外加 `makepkg`/`base-devel`** | `pacman`,Arch Linux | **不可以**——`makepkg` 是原生的 Arch 工具,没有跨平台等效实现;构建所用的主机本身必须是 Arch(或者 `archlinux/devtools` 容器镜像) |
| RPM(`packaging/nspawnmgr-rpm/`) | JDK 21 + Maven,**外加 `rpm-build`** | `dnf`,Fedora/RHEL | **不可以**——尽管 `rpm-maven-plugin` 名声在外,它实际上是真正 shell 调用了一个真实的 `rpmbuild` 二进制文件;已在实际环境中确认它在一台非 RPM 构建主机(例如 Windows)上会直接失败,没有跨平台等效实现,与 Arch 的 `makepkg` 情况相同 |

如果你手头没有多余的 Arch 或 Fedora 机器可以用来构建这些软件包,
`packaging/ci/arch-runner/bootstrap-arch-runner.sh` 和
`packaging/ci/fedora-runner/bootstrap-fedora-runner.sh` 展示了一种无需双系统启动或裸机部署即可
获得这类环境的方法:两者都会把一个真实的根文件系统构建进一个普通的 `systemd-nspawn` 容器(不是
Docker/Podman 镜像——nspawn 在这里被证明是最简单的方式,因为它默认共享主机的网络命名空间,不需要
为 CI 单独搭一个网桥)。`.gitea/workflows/build.yml` 中的 `arch-package` 和 `rpm-package`
任务展示了一旦这样的容器存在,具体会运行哪些构建命令(安装 JDK/Maven/原生打包工具,然后运行
`BUILD_ARCH_PKG=1`/`BUILD_RPM=1 tools/scripts/build-all.sh`,与下文展示的相同)。

### 选项 A:`.deb` 软件包(推荐)

**主机**仅限 Debian/Ubuntu——它所创建的自托管 `nspawnmgr`/数据库机器始终是 Debian,与主机本身无关,
依据[第1节](#1-架构概述)。它会处理第3节(具备 sudo 权限的账户、sudoers、SSH 密钥对),并创建并启动
自托管的 `nspawnmgr` 机器,其中已经安装好了 Tomcat、全部四个 WAR,以及 `guacd`——不过第6节*其余*的
内容并不能因此跳过:"启用 HTTPS"和"使用不同的端口"这两部分尤其仍然值得阅读(参见下文的"这之后仍需
手动完成的内容"),只是现在应用在那台机器内部,而不是主机上。安装完成后继续第7节。

**获取一个 `.deb`**,可以自己构建一个:

```bash
mvn -DskipTests install                          # root -> target/nspawnmgr.war (installed, not just packaged - the next module needs it)
mvn -f auth/pom.xml -DskipTests package          # -> auth/target/auth.war
mvn -f packaging/nspawnmgr-deb/pom.xml package   # -> packaging/nspawnmgr-deb/target/nspawnmgr_*.deb
```

(或者使用 `BUILD_DEB=1 tools/scripts/build-all.sh`,它执行相同的三个步骤——之所以需要这个环境变量,
是因为构建一个 `.deb` 首次使用时需要网络访问来获取 `jdeb` Maven 插件,而一次普通的开发构建不应该被
强制要求联网),或者从你团队发布的位置安装一个预构建好的版本——本仓库自身的 CI
(`.gitea/workflows/build.yml` 的 `publish-deb` 任务)会把每次成功的构建发布到一个 Gitea Debian
软件包仓库,作为一个可用的参考,如果你想为自己的分支/实例搭建同样的东西(需要一个仓库 Actions
密钥 `PACKAGE_REGISTRY_TOKEN`,一个具备软件包写入权限的 Gitea 访问令牌——参见该任务在工作流文件中
自己的注释)。

**安装它:**

```bash
sudo apt install ./nspawnmgr_0.4.0_all.deb   # pulls in openssh-server, openssl, dnsmasq, systemd-container - not a JRE, not tomcat9
```

`tomcat9` 和 `guacd`/`guacamole-tomcat` 都不在这个软件包的 `Depends:` 中——apt 自身的 `tomcat9`
可用性因发行版本而异,而 `guacd`/`guacamole-tomcat` 在任何当前发行版上根本都没有打包(参见
`packaging/nspawnmgr-deb/debian/control` 自身的说明)。`tomcat9`、`guacd` 和 `guacamole.war` 全部
改为直接打包在一起,不需要你做任何事(参见第6节和第7节)——第7节中唯一剩下的手动步骤是数据库支持的
认证扩展,因为那确实需要只有你才有的凭据。

**刚才自动发生了什么**(具体脚本请参见 `packaging/nspawnmgr-deb/debian/postinst` 和
`nspawnmgr-bootstrap-app-machine.sh`):

- 在**主机**上创建了一个 `nspawnmgr_exec` 系统账户;为它生成了一个随机密码(仅首次安装时生成——升级
  时不会改动)并写入 `/etc/nspawnmgr/nspawnmgr.env`(这就是第3节所说的那个"存储密钥"式的 sudo
  密码——具体含义以及如何切换到管理员审批模式,参见第3节);无论处于哪种模式,都生成了一个 SSH
  密钥对,并安装进该账户的 `authorized_keys`。第3节中的 NOPASSWD/密码等级划分被写入
  `/etc/sudoers.d/nspawnmgr_exec`,在被信任之前先用 `visudo -cf` 做了校验。
- 在主机上配置好了共享网桥(`nspawnbr0`)和 dnsmasq,与其他任何受管容器一样——参见上文的"按名称解析
  容器"。
- 构建了 `debian-minimal`(与 `/admin/templates` 上"设置 debian-minimal"会生成的是同一个 tar
  包),并将其克隆进一台名为 `nspawnmgr` 的全新机器。
- 在它还只是一个已解压的根文件系统、尚未启动之时:一个 JRE、打包好的 Apache Tomcat 9.0.120 压缩包、
  全部四个 WAR(`nspawnmgr.war`/`auth.war`/`guacamole.war`/`ROOT.war`),以及自包含的 `guacd`
  软件包(自带 OpenSSL 3.x、精简版 FFmpeg、FreeRDP2、libssh2)都被直接安装进了那台机器自己的文件
  系统——在其内部创建了 `tomcat`/`guacd` 系统用户,剥离了
  `manager`/`host-manager`/`examples`/`docs` 这几个 web 应用,用一个指向那台机器自己的 `guacd`
  的最小化 `guacamole.properties` 初始化了 `GUACAMOLE_HOME`,并解压安装了
  `guacamole-auth-jdbc` 以及两个 JDBC 驱动 jar(全程不需要任何网络访问——全部内容都已打包好,无需
  下载任何东西)。
- 一份重写过的 `/etc/nspawnmgr/nspawnmgr.env` 副本被写入那台机器内部(`SSH_HOST` 和
  `HOST_PUBLIC_ADDRESS` 被重新指向 `nspawnbr0` 自身的地址,而不是 `127.0.0.1`,这样 nspawnmgr 一旦
  启动,就能回连到主机上的 `nspawnmgr_exec` 账户),同时还有一份 SSH 私钥的副本。
- 挑选了一个空闲的主机端口(首先尝试 `8080`,如果已被占用就依次递增——安装期间会打印出具体是哪个),
  并通过其 `.nspawn` 文件中的 `Port=` 行,把它转发到那台机器自己的 `:8080`,这样
  `http://<该主机>:<该端口>/` 就能像非自托管安装一直以来那样到达 nspawnmgr。
- 启动了这台机器。其中的 Tomcat 启动后会提供 `ROOT.war` 的首次启动数据库向导(第4节)——此时还没有
  配置任何数据库,与以前一样,只是现在底层地址不同了。

**检查是否安装正确:**

```bash
sudo machinectl list                             # should show "nspawnmgr" running
sudo visudo -cf /etc/sudoers.d/nspawnmgr_exec    # should print "parsed OK"
curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port shown during install>/
```

Tomcat 相关的任何东西都不再运行在主机本身上了——不要在那里寻找 `tomcat9.service` 或
`/opt/tomcat9`;两者现在都存在于 `nspawnmgr` 机器内部(用 `sudo machinectl shell nspawnmgr` 进入
其中查看,或者一旦登录后,使用 nspawnmgr 自己的 SSH 访问功能连接它——参见第4节中关于它出现在容器
列表中的说明)。这个 `.deb` 从不会把 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 写入那台机器的
`nspawnmgr.env`——只写入 sudo/主机名相关的设置——所以上面的 curl 检查:

- **`200`**——还没有可用的数据库,所以你看到的是第4节"首次启动配置向导"中描述的那个向导。这是刚完成
  一次全新 `.deb` 安装后的正常状态;填写向导以继续。
- **`302`**(重定向到 `/nspawnmgr/`)——一个可用的数据库已经配置好了。跟随这个重定向,如果真正的应用
  正常启动,应该会看到另一个 `302`(指向登录页面);如果没有正常启动,则会看到 `404`:说明
  nspawnmgr 的 Spring 上下文启动失败了。在断定这个软件包本身有问题之前,先检查
  `sudo machinectl shell nspawnmgr journalctl -u tomcat9`(nspawnmgr 网页界面自己的"查看日志"页面
  在这里帮不上忙——因为 nspawnmgr 自己根本还没能启动到那一步);通常是那台机器自己的
  `/etc/nspawnmgr/nspawnmgr.env` 中缺失或错误的某个值(第9节介绍了每个设置项的含义)。

**这之后仍需手动完成的内容**:让首次启动向导(第4节)指向一台 MySQL/PostgreSQL 服务器——它会创建
`nspawnmgr` 和 `guacamole` 这两个数据库,运行两个应用各自的 schema,并为你接好 Guacamole 的
`guacamole-auth-jdbc` 扩展,但你仍然需要运行它一次,之后仍然需要创建 Guacamole 管理员账户;至少
一个容器模板(第2节的"容器模板"——在有模板之前什么都创建不了;全新安装从零开始,所以
`/admin/templates` 的一键"设置 debian-minimal"按钮一开始就是可用的);对照第9节检查/调整
`/etc/nspawnmgr/nspawnmgr.env` 中的其余内容(Guacamole 基础 URL 等等——生成的文件已经填好了 sudo
凭据、`APP_SECRET_KEY`,以及指向这台主机自己打包的 `auth.war` 的
`USER_ID_URL`/`AUTH_LOGIN_URL`,但没有填那些没有合理自动生成默认值的应用配置);启用
HTTPS(第6节的"启用 HTTPS"——`.deb` 默认让 Tomcat 保持在纯 HTTP 上,与手动安装路径一致;如果你在
使用管理员审批模式,根据那一节的说明强烈建议启用);以及验证(第10节)。

`postrm` 在软件包卸载/清除时,刻意从不删除 `nspawnmgr_exec` 或 `/etc/nspawnmgr`——那个账户是你的
容器保持可达的唯一凭据。

**要把一次已有的安装升级到一个更新的软件包构建**(一个 bug 修复,而不是全新安装):
`sudo /usr/lib/nspawnmgr/upgrade-nspawnmgr.sh <path-to-the-new-package-file>`。仅仅一次普通的
`apt install`/`dnf install`/`pacman -U`——甚至 `apt install --reinstall`——都是不够的:如果记录的
已安装版本字符串没有变化,这些命令可能会悄悄地什么都不做,而这一点很重要,因为一次开发周期内的每次
构建都会以相同的固定版本号重新发布。这个脚本会直接安装给定的软件包文件(始终应用其内容,不论记录的
版本号如何),这反过来会重新触发该软件包自己的安装后步骤——而那总是会调用
`nspawnmgr-bootstrap-app-machine.sh`,它每次调用时都会完整地协调自托管 `nspawnmgr` 机器的内容,
不仅仅是首次安装时:四个打包的 WAR、`guacd` 自己的软件包和服务、Tomcat 的服务单元,以及 SSH 回连
凭据文件都会被刷新,机器会围绕这个过程被停止/重启,以确保没有任何内容在仍在使用时被覆盖。它现有的
主机转发端口会在升级过程中被保留,不会重新选择。这个过程是非破坏性的——`/var/lib/machines`
(所有*其他*容器)和两个数据库都完全不受影响;机器内部的基础根文件系统克隆以及
`tomcat`/`guacd` 系统账户也都不受影响(重新触碰这些内容可能会破坏真实的管理员自定义配置,或者在
第二次运行时直接失败)——一次 Tomcat *版本*升级则仍然需要一次完整的重新安装,与以前一样。

**要彻底移除这一切**(测试机器,从头再来——不是那种应该在一台真实部署上不假思索直接运行的操作,因为
它会删除你的容器保持可达所依赖的 sudo/SSH 凭据):
`sudo /usr/lib/nspawnmgr/uninstall-nspawnmgr.sh`。在 `apt purge` 已经完成的工作之外,它还会移除
`/opt/tomcat9`、`/etc/nspawnmgr`、`/etc/guacamole`、`/var/lib/nspawnmgr/templates`
(`TEMPLATES_DIR`——模板 tar 包,包括"设置 debian-minimal"按钮下载的任何内容;一个残留的模板文件在
一次清除之后仍然存在,正是导致该按钮"必须尚不存在"这项检查在之后的重新安装中失败的原因)、
`tomcat`/`nspawnmgr_exec` 系统账户,以及 nspawnmgr 配置过的任何
[机器启动设置](#随主机启动自动启动)(自动启动单元的启用状态、要求另一台机器先启动的
drop-in)——这些纯粹是仅以机器名称为键的 systemd 单元文件状态,不会被 `apt purge` 甚至删除容器本身
所触及,而一个从上一次安装遗留下来的过期 `Requires=` drop-in 足以彻底破坏一次全新的重新安装
(`machinectl start nspawnmgr` 会因为"A dependency job for systemd-nspawn@nspawnmgr.service
failed."而失败,因为它所要求的那个单元已经不存在了)——这里列出的一切正是 `postrm` 刻意保留下来的
内容,是为了那些不希望这种保守做法的场景准备的。默认情况下它仍然**不会**触碰 nspawnmgr 自己的
数据库、Guacamole 自己的数据库,或 `/var/lib/machines`(你真正的容器)——只涉及它们周围的管理层
(以及用来创建它们的模板)——但它会单独询问(各自有自己的 y/n 提示,永远不会被 `--yes` 隐含默认)
是否也要删除 `nspawnmgr`/`guacamole` 数据库及其数据库用户(仅当 `DB_URL` 指向
`localhost`/`127.0.0.1` 时才支持,该信息在这些文件被移除之前从 `db.properties`/`nspawnmgr.env`
中读取),以及是否要移除当前在 `machinectl` 中注册的每一个容器。这对于在多次迭代之间快速重置一台
真实测试主机很有用,因为这两个步骤都是真正的数据丢失操作。

### 在 Arch Linux 上安装

构建和安装都已经在真实的 Arch 系家族系统上得到实际验证:针对这个确切的 `PKGBUILD` 运行
`makepkg -f`(acer 上的 `arch-runner` systemd-nspawn 容器——参见
`packaging/ci/arch-runner/`)会通过 `.gitea/workflows/build.yml` 的 `arch-package` 任务生成一个
真实的 `nspawnmgr-0.3.0-1-any.pkg.tar.zst`,而生成的软件包自身的 `pacman -U` 以及
`nspawnmgr.install` 钩子已经在一台真实的 SteamOS 系统上被反复实际执行(基于 Arch,运行
`steamos-readonly disable` 之后与 `pacman` 兼容)——全新安装、卸载/重装循环,以及通过
`upgrade-nspawnmgr.sh` 进行的原地升级,全部都已确认可以正常工作,包括自托管机器获得一个真实的网络
租约,以及 web 界面正确响应。有一个**独立**的软件包,`packaging/nspawnmgr-steamos/`,专门为 SteamOS
准备(参见它自己相对于这个软件包的 `provides`/`conflicts`——只安装两者中的一个,永远不要两者都装),
因为 SteamOS 较小的根分区需要把存储重新定位到 `/home` 下;这个普通的 Arch 软件包才是一台非 SteamOS
的 Arch 主机应该安装的。那条非 SteamOS 路径——把这个确切的软件包安装在真正原生的 Arch 上(相对于
SteamOS 而言,两者共享相同的底层 `pacman`/`systemd` 机制,但并不完全相同)——目前还没有被直接测试过;
如果你尝试的话,请反馈遇到的问题。

`packaging/nspawnmgr-arch/`(一个 `PKGBUILD` + `nspawnmgr.install`,不是一个 Maven 模块——不存在
原生支持 Maven 的 Arch 打包插件)在其他方面与上面的选项 A 采用相同的自托管架构:相同的
`nspawnmgr_exec` 账户/sudoers/网桥/dnsmasq 设置,相同的自托管 `nspawnmgr` 机器(不论这台主机自身
是什么发行版,始终仍然是 Debian-minimal——参见[第1节](#1-架构概述)——一台 Arch 主机并不会改变
自托管的*应用机器*本身运行什么,只会改变*裸机主机*本身需要什么),相同的"刚才发生了什么"、"检查是否
安装正确"以及"这之后仍需手动完成的内容",与选项 A 完全一致——请参见上文,那些内容在这里同样适用。
差异之处很有限:

- **依赖项**:`openssh`、`openssl`、`dnsmasq`——不需要 JRE,不需要
  `apache2-utils` 的等效物(两者都安装在自托管的应用机器*内部*,裸机主机上根本不需要——参见
  `nspawnmgr-bootstrap-app-machine.sh`),不需要 `systemd-container` 的等效物
  (`machinectl`/`systemd-nspawn` 已经包含在 Arch 自己的基础 `systemd` 软件包中)。
- **没有防火墙步骤**:与 `.deb` 的 `ufw` DHCP 特殊例外不同,Arch 默认不启用任何防火墙,所以没有什么
  需要绕过的。如果你自己配置了 `nftables`/`iptables`/`ufw`,请确保 `nspawnbr0` 上的入站 UDP/67
  端口是被允许的(与 `.deb` 自身的 `ufw` 步骤所满足的需求相同)。
- **移除操作默认保持保守**:`pacman -R`/`-Rns` 并不像 `dpkg`/`apt` 那样提供清除与移除的区分,所以
  `nspawnmgr.install` 的 `post_remove()` 刻意做得和 `postrm` 自身默认(非清除)行为一样少——与
  `.deb` 使用同一个 `uninstall-nspawnmgr.sh` 脚本来处理完整清理,安装在相同路径。

构建和安装:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_ARCH_PKG=1 tools/scripts/build-all.sh   # needs `makepkg` on PATH - a real Arch host, or the
                                               # archlinux/devtools container image

sudo pacman -U packaging/nspawnmgr-arch/nspawnmgr-0.4.0-1-any.pkg.tar.zst
```

### 在 Fedora/RHEL(RPM)上安装

构建和安装都已经在一台真实的、处于 `Enforcing` SELinux 模式下的 Fedora 43 主机上得到实际验证
(用于构建的 acer 上的 `fedora-runner` systemd-nspawn 容器——参见
`packaging/ci/fedora-runner/`——以及一台独立的 `fedora-test-vm` QEMU 客户机用于安装验证):真实的
端到端流程(数据库配置向导、登录、容器创建,以及通过 `upgrade-nspawnmgr.sh` 反复进行的原地升级)已经
确认可以正常工作,包括在 SELinux Enforcing 模式下具体也是如此。

`packaging/nspawnmgr-rpm/`(一个真正的 Maven 模块——`rpm-maven-plugin` 确实是真正 shell 调用了
`rpmbuild`,尽管看起来不像,但它并不是纯 Java 实现)在其他方面与上面的选项 A 采用相同的自托管架构——
相同的 `nspawnmgr_exec` 账户/sudoers/网桥/dnsmasq 设置,相同的自托管 `nspawnmgr` 机器(不论这台
主机自身是什么发行版,始终仍然是 Debian-minimal),相同的"刚才发生了什么"、"检查是否安装正确"以及
"这之后仍需手动完成的内容",与选项 A 一致。差异之处很有限:

- **依赖项**:`openssh-server`、`openssl`、`dnsmasq`、`systemd-container`,以及
  `iptables-nft`——Fedora 那个基于 nftables、真正提供 `/usr/bin/iptables` 的软件包(纯粹叫
  `iptables` 的软件包名称在 Fedora 上并不存在;不论底层实现是什么,每容器出站互联网开关都需要一个
  真实的 `iptables` 二进制文件)。
- **firewalld 特殊例外**:Fedora 默认激活了 `firewalld`。安装过程会把 `nspawnbr0` 加入 firewalld 的
  `trusted` 区域并重新加载——如果不这样做,firewalld 的默认区域策略会悄悄阻止 DHCP 租约发放给
  容器,失败方式与下面 SteamOS 自己的 `firewalld` 特殊例外相同。
- **SELinux 策略模块**:在 `Enforcing` 模式下,`systemd_machined_t` 需要一个小的自定义策略模块
  (`nspawnmgr_machined_cgroup.te`,在安装时通过 `checkmodule`/`semodule_package`/`semodule -i`
  从源码编译,而不是作为预编译的 `.pp` 打包,这样才能匹配实际运行的具体策略版本),授予对
  `cgroup_t` 文件的 `watch` 权限——这是任何标准 Enforcing 模式 Fedora 主机上普遍存在的一个 SELinux
  策略缺口,与 nspawnmgr 本身无关,但如果不处理,它会让每一次
  `machinectl`/`systemd-nspawn` 容器启动都以"Failed to register machine: Access denied."失败。
- **移除操作默认保持保守**,姿态和使用的脚本(同一个 `uninstall-nspawnmgr.sh`)都与另外两种软件包
  格式相同。

一个环境拓扑方面需要注意的地方,不是代码 bug:`AUTH_LOGIN_URL` 自动检测到的主机名,需要能够从浏览器
实际连接的地方被解析(这是一个刻意的设计选择——参见[第9节](#9-配置-nspawnmgr)——用来避免一种更糟糕
的、与 cookie 作用域相关的登录死循环)。这具体可能会在通过 NAT/隧道/端口转发拓扑进行测试时出问题,
而不是一个可以直接访问的真实主机名;这种情况下请手动调整 `AUTH_LOGIN_URL`。

构建和安装:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_RPM=1 tools/scripts/build-all.sh   # needs a real `rpmbuild` binary (`rpm-build` package) -
                                          # a real Fedora/RHEL host, no cross-platform equivalent

sudo dnf install ./packaging/nspawnmgr-rpm/target/rpm/noarch/nspawnmgr-0.4.0-1.noarch.rpm
```

### 选项 B:从源码构建,手动部署

**这条路径直接把 Tomcat 部署在你正在操作的这台主机上——它不会像选项 A 那样把 nspawnmgr 自托管进它
自己的机器中。**这没关系;自托管是 `.deb` 的 `postinst` 所做出的一个特定选择,不是一个硬性要求——
一次手动构建、Tomcat 直接跑在主机上的部署仍然是完全受支持的,只是一种更早期/更简单的拓扑结构。
如果你想要不通过 `.deb` 就获得自托管模式,最直接的方式是通读
`nspawnmgr-bootstrap-app-machine.sh` 并手动执行它所做的事情(构建一个模板、克隆它、把 JRE/Tomcat/
WAR 安装进那个容器的根文件系统等等),而不是按照下面的第6节操作——那一节是把 Tomcat 部署在主机本身
上,与它一直以来的方式相同。

从仓库根目录:

```bash
mvn -DskipTests package                # -> target/nspawnmgr.war
mvn -f auth/pom.xml -DskipTests package  # -> auth/target/auth.war
```

(`tools/scripts/build-all.sh` 同时完成这两步,外加仅用于开发的伪造模块——真实部署不需要这些伪造
模块。)继续第6节,完成 `.deb` 原本会替你完成的手动 Tomcat/账户/sudoers 设置。

`.deb` 的 `postinst` 还会创建 `/etc/nspawnmgr/auth-live/`,归属 `tomcat:tomcat`,权限
`750`——这是 `/admin/settings` 写入 auth.war 实时配置的共享文件(参见
[第9节](#9-配置-nspawnmgr))。一次手动部署也需要同样的目录,一旦 Tomcat 的 `tomcat` 用户存在
(第6节):

```bash
sudo mkdir -p /etc/nspawnmgr/auth-live
sudo chown tomcat:tomcat /etc/nspawnmgr/auth-live
sudo chmod 750 /etc/nspawnmgr/auth-live
```

## 6. Tomcat 9(nspawnmgr + Guacamole + auth)

**本节描述的是把 Tomcat 直接部署在主机上**——这是手动安装(第5节选项 B)所采用的方式。如果你是通过
`.deb`/Arch/RPM 软件包安装的(第5节选项 A),Tomcat 根本不在主机上——它在自托管的 `nspawnmgr`
机器内部,已经由 `nspawnmgr-bootstrap-app-machine.sh` 设置好了,本节内容都不适用;请直接跳到第7节。

Guacamole 的官方 web 应用仍然面向 `javax.servlet`,所以它和 nspawnmgr 被并排部署进**同一个
Tomcat 9** 实例。

**不是 apt 依赖项。**与 `guacd`(第7节)一样,apt 的 `tomcat9` 软件包的可用性因
Debian/Ubuntu/Mint 发行版本不同而差异很大,所以本项目改为打包一个原生上游的 Apache Tomcat 二进制
发行版,而不是依赖它——使用的是当前的一个补丁版本(9.0.120),而不是某个 apt 仓库碰巧提供的版本,
并且这个软件包完全独占这整个实例(`/opt/tomcat9`、它自己的 `tomcat` 系统用户、它自己的
`tomcat9.service`)。**如果之前版本的这个软件包(那时确实依赖 apt 的 `tomcat9`)已经安装了,请先
移除那个软件包的 `tomcat9`**——两个 Tomcat 实例同时尝试绑定 `:8080` 会失败。

否则(选项 B),请解压 `.deb` 所使用的同一个打包好的压缩包——一份仓库检出中的
`packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz`——而不是自己重新下载一份,这样一次
手动安装就能与本项目测试所针对的确切补丁版本保持一致:

```bash
sudo mkdir -p /opt/tomcat9
sudo tar -xzf packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz -C /opt/tomcat9 --strip-components=1
sudo chmod +x /opt/tomcat9/bin/*.sh
```

让 Tomcat 以它自己的、无特权、非 sudo 的系统用户身份运行(永远不是 root,并且刻意不与
[第3节](#3-具备-sudo-权限的-ssh-账户)使用同一个账户):

```bash
sudo useradd -r -M -d /opt/tomcat9 -s /usr/sbin/nologin tomcat
sudo chown -R tomcat:tomcat /opt/tomcat9
```

**如果你是在这一步之前先完成了[第3节](#3-具备-sudo-权限的-ssh-账户)**(文档记录的顺序),现在请回去,
让刚刚创建好的这个 `tomcat` 用户能够读取第3节生成的那个 SSH 密钥对
(`SSH_PRIVATE_KEY_PATH`,默认为 `/etc/nspawnmgr/ssh_id_ed25519`)——`SshRemoteExecutor` 会在每次
特权操作时,直接从 Tomcat 自身的进程内部打开这个文件,而这个密钥在创建时是 `root:root`、权限
`600`(完全没有组访问权限),因为在那个时间点 `tomcat` 用户还不存在:

```bash
sudo chown root:tomcat /etc/nspawnmgr/ssh_id_ed25519
sudo chmod 640 /etc/nspawnmgr/ssh_id_ed25519
```

跳过这一步会导致每一个特权操作都以"Failed to establish SSH connection to 127.0.0.1:22"失败——尽管
措辞是这样,但这实际上是一个权限问题,不是一个连接问题。

上游的压缩包中打包了 `manager`/`host-manager`/`examples`/`docs` 这几个 web 应用,而 Debian 自己的
`tomcat9` 软件包会把它们拆分成默认不会安装的独立子软件包;`.deb` 的 `postinst` 在首次安装时会剥离
这些内容,原因相同——如果保持部署且未加配置,这是真实的、可以避免的攻击面——这里也值得手动做同样的
处理:

```bash
sudo rm -rf /opt/tomcat9/webapps/manager /opt/tomcat9/webapps/host-manager \
       /opt/tomcat9/webapps/examples /opt/tomcat9/webapps/docs
```

部署 nspawnmgr:

```bash
sudo cp target/nspawnmgr.war /opt/tomcat9/webapps/nspawnmgr.war
```

nspawnmgr、Guacamole 和 `auth`(第8节)下面各自占用自己的上下文路径——它们中没有一个可以在不放弃
这条路径的情况下独占服务器根路径——所以在裸 `http://<hostname>:8080/` 处放一个小小的静态重定向
页面,可以参考本仓库自身的 `site/root-index/index.html`(会重定向到 `/nspawnmgr/`):

```bash
sudo mkdir -p /opt/tomcat9/webapps/ROOT
sudo cp site/root-index/index.html /opt/tomcat9/webapps/ROOT/index.html
sudo chown -R tomcat:tomcat /opt/tomcat9/webapps/ROOT
```

在 Tomcat 启动过程外层的包装中设置 `SPRING_PROFILES_ACTIVE=prod`(以及
[第9节](#9-配置-nspawnmgr)中的其他每一个环境变量)(一个 systemd 单元的
`Environment=`/`EnvironmentFile=`,或者 `bin/setenv.sh` 下的 `CATALINA_OPTS`——如果某个 `-D` 值
中包含 `;`,请给它加上引号,因为 `catalina.sh` 会把 `$CATALINA_OPTS` 当作一条 shell 命令行重新求值,
一个未转义的 `;` 会被解析成一个命令分隔符,悄悄截断整个启动命令)。如果没有激活任何 profile,
nspawnmgr 会默认使用 `dev`(内存中的 H2,伪造的执行器)——这不是你在这里想要的。

把它设置为一个 systemd 服务,这样它才能在重启后存活,例如
`/etc/systemd/system/tomcat9.service`(与 `.deb` 安装的是同一个单元——一份仓库检出中的
`packaging/nspawnmgr-deb/tomcat9.service` 是一份现成的参考):

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

使用 `Type=simple` 配合 `catalina.sh run`(前台运行),而不是 `Type=forking` 配合
`startup.sh`/`shutdown.sh`——这样 systemd 会直接监督这个 JVM,所以一次崩溃能被检测到,
`Restart=on-failure` 才能真正触发;一个 forking 类型的单元只知道*包装脚本*是否退出了,不知道
Tomcat 本身是否还存活。

```bash
sudo systemctl enable --now tomcat9
```

### 使用不同的端口

Tomcat 默认监听 `8080`(`conf/server.xml` 中的 `<Connector port="8080" .../>`)。要修改它,直接编辑
那个 `port` 属性:

```bash
sudo sed -i 's/port="8080"/port="8180"/' /opt/tomcat9/conf/server.xml
```

或者使用 `/admin/settings` 上的**Tomcat**部分,而不是手动编辑 `server.xml`——它读写的是同一个文件
(通过 `catalina.base` 这个 JVM 系统属性来定位,Tomcat 自身的启动脚本总是会设置它,所以不论你运行的
是 `.deb` 打包的 Debian 版 `tomcat9`,还是 `/opt/tomcat9` 下手动解压的一份,它都能找到正确的
`server.xml`),走的是与其他所有特权操作已经使用的相同的、具备 sudo 权限的 SSH 账户,以及
`nspawnmgr-write-file.sh` 封装脚本——不需要新的 sudoers 授权。真正**具有权威性的是文件本身**,不是
数据库中的一份副本:这个页面始终显示和编辑磁盘上实际存在的内容,所以手动编辑 `server.xml`(如上所示)
和使用设置页面是完全可以互换使用的——两者都不会相对于对方过期。

本指南中(以及你自己配置中——`nspawnmgr.auth.user-id-url`/`AUTH_LOGIN_URL`、
`nspawnmgr.guacamole.base-url`,以及你告诉用户去访问的那个 URL)出现的每一个其他 `:8080` 都必须相应
更新——不论你用哪种方式修改端口,都不会有任何东西自动从 `server.xml` 推导出这个端口。在
`/admin/settings` 上,这大多数情况下只需要每个字段点一下:上述每个 URL 字段都有一个"刷新
主机名/端口/协议"按钮,会根据 Tomcat 部分当前的端口/HTTPS 状态,以及 `host.external-hostname`
(第8节)重写这个字段——不需要逐个手动编辑每个 URL 的端口。如果你在防火墙后面,请确保新端口是开放的,
而不是 `8080`。不论哪种方式,变更都只会在重启后生效——使用 `/admin/settings` 上的"重启 Tomcat"按钮
(见上文),或者自己运行 `sudo systemctl restart tomcat9`。

### 启用 HTTPS

两种方式,按照真实部署实际采用的先后顺序排列:

1. **在 Tomcat 前面用一个反向代理来终止 TLS**(nginx、Apache、Caddy,或者一个云负载均衡器),让
   Tomcat 仅保持在 `127.0.0.1:8080` 上监听纯 HTTP(在 `server.xml` 的
   `<Connector address="127.0.0.1" .../>` 中把它绑定到回环地址,这样它就无法被直接访问)。这通常是
   证书续期(例如 Certbot/Let's Encrypt)更简单的路径,因为它与 Tomcat 自身的密钥库格式是解耦的。
   把本指南中每一个 `nspawnmgr.*`/`AUTH_LOGIN_URL` 的 URL 都指向
   `https://<hostname>/...`(不论代理监听哪个端口),而不是
   `http://<hostname>:8080/...`——[第8节](#主机名与共享会话-cookie)中的主机名/
   cookie 相关要求,实际上适用的是这个代理,而不是 Tomcat。

2. **如果你不想运行反向代理,可以直接配置一个 Tomcat SSL 连接器。**从 Tomcat 8.5/9 开始,
   `<SSLHostConfig>` 的 `<Certificate>` 元素可以直接接受一个 PEM 格式的证书/密钥
   (`certificateFile`/`certificateKeyFile`/`certificateChainFile`)——不需要转换成 Java 密钥库
   格式,这一点很重要,因为这正是 Let's Encrypt/ACME 客户端(例如 Certbot)交给你的格式
   (`fullchain.pem`/`privkey.pem`)。把 Certbot 指向这台主机(`certbot certonly --standalone -d
   nspawnmgr.example.com`,或者任何适合你环境的插件),然后往 `server.xml` 添加一个连接器:

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

   `tomcat` 系统用户需要能读取 `/etc/letsencrypt/live/.../*.pem`(Let's Encrypt 自己的目录默认通常
   仅限 root 访问——要么单独放开这两个文件的权限,要么把它们复制到 Tomcat 可以读取的地方,并在每次
   续期时重新复制)。重启 Tomcat,然后在本指南中所有地方,把 `http://<hostname>:8080/...` 改用
   `https://<hostname>:8443/...`。要么彻底移除纯 HTTP 连接器,要么设置它的
   `redirectPort="8443"`,这样一个误发的 HTTP 请求会被弹回到 HTTPS,而不是以明文提供服务。
   Certbot 的续期本身不会替你重启 Tomcat——请添加一个
   `--deploy-hook "systemctl restart tomcat9"`(或者一个 `renewal-hooks/deploy/` 脚本),这样
   续期后的证书才能真正生效。

   `/admin/settings` 上的**Tomcat**部分,正是用来构建/编辑这个确切的连接器代码块的——一个"HTTPS"
   下拉菜单,加上两个 PEM 路径——使用的是与上文"使用不同的端口"中描述的相同的、以文件为权威来源、
   通过 SSH 封装脚本的机制。它从不会替你移除纯 HTTP 连接器或设置 `redirectPort`,并且保存时总是
   完全替换现有 `<Certificate>` 元素的路径,而不是合并——如果你已经把这个连接器自定义到超出这里所
   展示的范围(一个非 `RSA` 的证书类型、多个 `SSLHostConfig` 条目等),请改为手动编辑
   `server.xml`。

不论你选择哪种方式,本指南其他地方引用的每一个 `http://` URL——包括
`application.yml`/环境变量内部的,不仅仅是浏览器看到的——都需要相应改成 `https://`;nspawnmgr
被配置的内容与实际提供服务的内容之间的不匹配,是重定向死循环或 cookie 未发送失败的常见原因。

**如果你在使用管理员审批模式**([第3节](#3-具备-sudo-权限的-ssh-账户)),即使没有别的理由促使你这样
做,这里也强烈建议启用 HTTPS:审批页面会以一个普通表单字段的形式提交管理员的 sudo 密码,这在明文
HTTP 下的暴露程度,比 nspawnmgr 提供的其他任何东西都要大得多。文档记录的默认安装方式仍然是
HTTP——这只是针对这一种特定模式的建议,不是对默认设置的改动。

## 7. Guacamole

**Guacamole 的三个组件在任何当前的 Debian/Ubuntu/Mint 发行版上都不是 apt 软件包**:`guacd` 和
`guacamole-tomcat` 在 bookworm、trixie、jammy 和 noble 上搜索结果都是零,即使是 Debian
unstable,也只为 `ia64`/`riscv64` 构建了 `guacd`,并没有 `amd64`。这三者的处理方式各不相同,而且
单独安装其中任何一个都无法得到一套可用的配置:

| 组件 | 是否打包? | 作用 |
|---|---|---|
| `guacd` | **没有。**`.deb` 改为打包一个自包含的构建版本(自带 OpenSSL 3.x、精简版 FFmpeg、FreeRDP2 和 libssh2——具体原因和方式请参见 `/usr/share/doc/nspawnmgr/guacd-bundle-README.md`),并将其作为自己的 `guacd.service` systemd 单元运行——不论哪种安装方式,都不需要系统软件包,也不需要任何手动步骤。 | 原生代理守护进程 |
| `guacamole-tomcat` | **没有。**同样没有打包(它本来是负责替你部署 `guacamole.war` 的*打包胶水层*)——但 `guacamole.war` 本身是打包的:`.deb` 会直接把它部署进打包好的 Tomcat 中,与 `nspawnmgr.war`/`auth.war` 一样(见下文)。 | 把 `guacamole.war` 自动部署进 Tomcat |
| `guacamole-auth-jdbc` | **没有。**不是一个 apt 软件包,但打包方式与 `guacd` 相同——一个下载一次、经过校验和验证、提交进 `packaging/nspawnmgr-deb/vendor/` 的 tar 包(参见 `vendor/README.md`),不是在安装时才重新获取的。`.deb` 的 `postinst` 会自动解压它,不需要网络;手动安装则手动运行同一个脚本(见下文)。**这是必需的,不是可选的**——见下文。 | 为 Guacamole 提供 MySQL/PostgreSQL 连接存储后端的 JDBC 扩展,以及它的 SQL schema 脚本 |

`guacamole-auth-jdbc` 并不是几个可供选择的后端之一——nspawnmgr 通过 Guacamole 的 REST API 管理每一
个 Guacamole 连接和用户(参见下文的"GUACAMOLE_HOME 与认证后端"),而这个 API 只有在 Guacamole 运行着
一个数据库支持的认证扩展时才存在。Guacamole 自身的默认方式(`user-mapping.xml`,一个没有 API 的静态
XML 文件)不会暴露它。跳过这一步不会让你得到一个功能受限但仍可用的 nspawnmgr——它会让你得到一个完全
无法创建或管理任何容器连接的 nspawnmgr,因为每一个"把这个用户对这个容器的访问权限授予出去"的操作,
最终都要调用这个 API。即使借助 `.deb` 的自动化,解压这个 tar 包也只完成了下面第7节步骤1所描述内容的
一半——JAR/驱动仍然需要手动复制进 `GUACAMOLE_HOME`,而且不论是 `guacd` 还是 `guacamole.war` 被部署,
都不代表这些工作已经完成;请单独确认。

### guacd

如果你是通过 `.deb`(第5节选项 A)安装的,这一步已经完成了——`nspawnmgr-bootstrap-app-machine.sh`
把这个自包含的软件包解压到了 `/opt/guacd-bundle`,并且**在自托管的 `nspawnmgr` 机器内部**(而不是
主机上)启动了 `guacd.service`(用 `sudo machinectl shell nspawnmgr systemctl status guacd`
来确认)——直接跳到下面的"guacamole.war"。

否则(选项 B,Tomcat 部署在主机上——[第6节](#6-tomcat-9nspawnmgr--guacamole--auth)),你需要从
某处获得一个真实的 `guacd` 二进制文件,因为 apt 在任何当前发行版上都不会提供它。最直接的方式是复用
`.deb` 打包的同一个自包含构建版本:一份仓库检出中的
`packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz`(或者按照
`packaging/nspawnmgr-deb/vendor/README.md` 的方法自己构建一份——文档记录了每一个步骤,包括两个真正
耗费了不少时间才找到的陷阱:CMake 会在多次重新配置之间悄悄缓存一个过期的 OpenSSL 路径,以及单独的
`-Wl,-rpath` 如果没有配套的 `-L` 是不够的)。解压它,并像 `postinst` 那样安装 systemd 单元:

```bash
sudo tar -xzf packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz -C /opt
sudo adduser --system --home /nonexistent --no-create-home --group guacd
sudo cp packaging/nspawnmgr-deb/guacd.service /etc/systemd/system/guacd.service
sudo systemctl daemon-reload
sudo systemctl enable --now guacd
```

### guacamole.war

如果你是通过 `.deb`(第5节选项 A)安装的,这一步也已经完成了——
`nspawnmgr-bootstrap-app-machine.sh` 已经**在自托管的 `nspawnmgr` 机器内部**,通过一个指向
`/usr/share/nspawnmgr/guacamole.war` 的上下文描述符,部署了
`packaging/nspawnmgr-deb/vendor/guacamole-1.5.5.war`(同一个官方 Apache 发行版,下载一次并经过
校验和验证,不是在安装时才重新获取),与 `nspawnmgr.war`/`auth.war` 并列。用
`curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<forwarded port>/guacamole/` 确认
(期望得到 `200`,或者一个进入 Guacamole 自身登录流程的重定向),然后直接跳到下面的
"GUACAMOLE_HOME 与认证后端"。

否则(选项 B,Tomcat 部署在主机上),自己下载并部署同一个文件:

```bash
GUACAMOLE_VERSION=1.5.5
curl -fsSL -o guacamole.war \
  "https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/guacamole-${GUACAMOLE_VERSION}.war"
sudo cp guacamole.war /opt/tomcat9/webapps/guacamole.war
```

### GUACAMOLE_HOME 与认证后端

Guacamole 需要它自己的 `GUACAMOLE_HOME`(通常是 `/etc/guacamole`),其中包含
`guacamole.properties`,以及作为它**连接存储后端**的 `guacamole-auth-jdbc` 扩展 JAR/JDBC
驱动——这与 nspawnmgr 自身的数据库是完全独立的两回事。**第4节的首次启动数据库向导现在会自动完成下面
步骤1-2的内容**(把正确的扩展 JAR 复制进去,写入 `<vendor>-*` 属性,运行 schema),作为配置
`guacamole` 数据库的一部分——下面的流程是介绍如何手动完成这一切(没有向导访问权限、自动接线失败并
留下了一条警告,或者你事后要更换数据库)。如果你是通过 `.deb` 安装的,这个目录以及一份最小化的
`guacamole.properties`(只有 `guacd-hostname`/`guacd-port`,指向同一次安装已经启动的那个
`guacd` 实例)已经存在了,归属 `tomcat:tomcat`——只在首次安装时创建一次,所以之后的编辑(不论是
手动的,还是通过 `/admin/settings` 的 Guacamole 编辑器)总能在升级后保留下来。否则(选项 B),请
自己创建它:`sudo mkdir -p /etc/guacamole && sudo chown tomcat:tomcat /etc/guacamole`。如上文
所述,JDBC 认证扩展本身是必需的,不是几个备选方案中的一个:nspawnmgr 使用一个管理员账户
(`nspawnmgr.guacamole.admin-username`/`admin-password`),通过 Guacamole 的 REST API 管理
连接/用户,而只有 `guacamole-auth-jdbc` 会暴露那个 API。所以:

1. 把 `guacamole-auth-jdbc` 的 tar 包解压——与上面的 `guacd`/`guacamole-tomcat` 不同,这在任何发行
   版上都没有对应的 apt 软件包,但与 `guacd` 一样,它是直接打包在一起的,而不是在安装时下载:一份
   仓库检出中的 `packaging/nspawnmgr-deb/vendor/guacamole-auth-jdbc-1.5.5.tar.gz`,与 `.deb`
   打包的是同一个 tar 包,已经下载一次并针对 Apache 自己的 `.sha256` 完成了校验和验证。
   `install-guacamole-auth-jdbc.sh` 会把它解压(不需要网络)到一个固定的、与版本无关的
   **既定安装位置**,`/etc/guacamole/guacamole-auth-jdbc/`(`mysql/schema/` 和
   `postgresql/schema/` 两个子文件夹,不论你最终使用哪个数据库——这个 tar 包两者都打包了)。这不是
   Guacamole 本身要求的路径,只是 nspawnmgr 自己的约定:
   - **`.deb` 安装**:这一步作为 `postinst` 的一部分已经自动运行过了——如果失败了(例如那个 tar
     包碰巧从 `/usr/share/nspawnmgr/` 中缺失),可以手动重新运行
     `sudo /usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh`。
   - **手动安装**,或者要重新执行这一步(例如要升级 Guacamole 版本——请先重新打包 tar
     包):从一份仓库检出中运行
     `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh`(提供了
     `--source-tarball`/`--target-dir`/`--force` 参数——参见该脚本自身的头部注释)。

   不论哪种方式,都要从 `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/` 中,把你所选数据库
   (`nspawnmgr.guacamole.data-source`,例如 `mysql`)对应的扩展 JAR 复制到
   `GUACAMOLE_HOME/extensions/`——这仍然是一个手动步骤,因为它取决于一个没有任何东西可以替你做出的
   选择(选哪个数据库)。

   JDBC 驱动本身(真正的 `java.sql.Driver`,与上面的扩展 JAR 是不同的东西——`guacamole-auth-jdbc`
   从不打包它)则是另一回事:nspawnmgr.war 已经为自己无关的数据库用途打包了 MySQL 和 PostgreSQL
   两个驱动(根 `pom.xml`),所以与其再单独下载一次,`install-guacamole-jdbc-drivers.sh` 只是把
   nspawnmgr 自己已经构建好的这两个驱动 jar 复制进 `GUACAMOLE_HOME/lib/`——完全不需要网络访问,而且
   即使只用到其中一个,两个都放在那里也没有坏处。与上面的 schema tar 包一样,这一步也已经作为
   `.deb` 的 `postinst` 的一部分自动运行过了(尽力而为——如果因为某种原因失败了,可以重新运行
   `sudo /usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh`);对于手动安装,在运行
   `mvn -DskipTests package` 之后,从一份仓库检出运行
   `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh --source-dir target/guacamole-jdbc-drivers`。
2. 针对一个由 Guacamole 拥有的数据库运行该扩展的 schema 脚本(这**不是**与 nspawnmgr 自身相同的
   数据库——Guacamole 需要它自己的用户/连接 schema)。`/admin/settings` 上的 Guacamole 部分有一个
   **"测试数据库连接"**按钮可以替你完成这一步:它会用数据库字段中当前填写的内容连接数据库,检查
   schema 看起来是否已经配置好(探测 `guacamole_connection` 表是否存在),如果没有,就会提议运行你
   指定的某个目录下的每一个 `.sql` 文件——"Schema 脚本目录"字段默认就是
   `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/schema`(与它上方选择的数据库类型
   匹配),所以如果第1步使用的是既定位置,这通常只需要点一下"测试"就行,不用改任何东西。
3. 创建 nspawnmgr 将要使用的管理员账户(`guacadmin`/`guacadmin` 是这个 JDBC 扩展首次运行时自带的
   知名默认账户——在真实部署中请立即修改这个密码,并同步更新
   `nspawnmgr.guacamole.admin-password`)。
4. 在 `guacamole.properties` 中设置 `guacd-hostname`/`guacd-port`(默认是 `localhost:4822`,如果
   guacd 运行在同一台主机上就没问题)。

把文件放进 `GUACAMOLE_HOME` 之后重启 Tomcat——Guacamole 不会热重载扩展。

一旦它启动,把 nspawnmgr 指向它(`nspawnmgr.guacamole.base-url`),例如
`http://your-hostname:8080/guacamole`。如果你使用了非默认路径,也要设置
`nspawnmgr.guacamole.home`(`GUACAMOLE_HOME`,默认 `/etc/guacamole`)——这正是
`/admin/settings` 的 Guacamole 编辑器读写 `guacamole.properties` 所使用的路径(参见
[第9节](#9-配置-nspawnmgr))。不需要额外设置权限:nspawnmgr 和 Guacamole 在同一个 Tomcat 实例中
以相同的 `tomcat` 用户身份运行,而 `GUACAMOLE_HOME` 已经因为 Guacamole 自身的使用而归属
`tomcat` 了。

## 8. `auth`(登录后端)

`auth.war` 是真正负责针对你的操作系统账户(PAM)或一台通过 SMB 的 Windows 机器来核对用户名/密码,
并签发 nspawnmgr 所信任的共享会话 cookie 的组件。它面向 `javax.servlet`(Servlet
4.0),与 nspawnmgr 和 Guacamole 的 web 应用相同,所以它被部署进第6节所述的**同一个 Tomcat 9
实例**——不需要一个单独的 servlet 容器。(仅用于快速本地迭代的话,它也可以通过
`mvn -f auth/pom.xml jetty:run` 独立运行,在 Jetty 上以 9092 端口启动,不需要重新构建/重新部署
WAR——不是你会用于真实部署的方式。)

通过 `auth/src/main/webapp/WEB-INF/web.xml` 中的 context-params 来设置这些内容(修改后需要重新
构建 WAR),或者使用该文件中记录的对应系统属性(`-D...`):

| 设置项 | 系统属性 | 用途 |
|---|---|---|
| `auth.backend` | `AUTH_BACKEND` | `pam`(默认,auth 自身所在主机上的本地 Linux 账户)或 `smb`(远程 Windows 机器) |
| `smb.server` | `SMB_SERVER` | 当 `auth.backend=smb` 时必填——要针对其进行认证的 Windows 主机 |
| `smb.domain` | `SMB_DOMAIN` | 可选的 NTLM 域 |
| `auth.required-group` | `AUTH_REQUIRED_GROUP` | 可选,仅 `pam` 模式——一个 Unix 组;已认证但不属于该组成员的用户会被拒绝登录 |
| `smb.required-share` | `SMB_REQUIRED_SHARE` | 可选,仅 `smb` 模式——`smb.server` 上的一个 SMB 共享;除非用户对该共享有访问权限,否则拒绝登录(下面会说明为什么这是一个共享检查,而不是一个组检查) |
| `cookie.name` | — | 必须与 nspawnmgr 的 `nspawnmgr.auth.cookie-name` 一致(默认 `nspawnmgr_session`) |

**为什么 `smb` 是基于共享访问权限,而不是组成员身份来把关的:**Windows 默认把*远程*
SAM/组查询限制为仅 `BUILTIN\Administrators` 可用(`RestrictRemoteSAM`)——这会导致普通用户永远无法
通过组检查,不论如何调整注册表都是如此,这是设计使然。共享访问是一个普通的、受 ACL 控制的 SMB
操作,没有这样的限制,所以请通过在 `smb.required-share` 上为应该/不应该被允许登录的用户设置普通的
共享和 NTFS 权限,来实现授权/拒绝。

**`pam` 需要 Tomcat 账户拥有对 `/etc/shadow` 的可读访问权限。**通过 PAM 验证一个密码,最终意味着要
从目标用户的哈希值中读取 `/etc/shadow`(权限 `640`,`root:shadow`)——通常这会透明地通过
`pam_unix` 自身那个 setgid-`shadow` 的 `unix_chkpwd` 辅助程序来处理,不论调用进程自身属于哪个组,
但这个回退机制并不是在每台主机上都可靠(一次真实安装恰好遇到了这个问题:`unix_chkpwd` 的 setgid
提权对*任何*非 root 调用者都悄悄没有生效,导致每一次 PAM 登录都以一个简单的"Login failed"失败,
`auth.war` 自己的日志里也没有任何可操作的错误信息)。`.deb` 的 `postinst` 直接把 `tomcat` 加入
`shadow` 组(`usermod -aG shadow tomcat`)来绕开这个问题——这样 `pam_unix` 就可以自己直接读取
`/etc/shadow`,两种方式都不再需要 `unix_chkpwd` 这个回退机制。一次手动(非 `.deb`)安装也需要
同样的处理:`sudo usermod -aG shadow tomcat`,然后重启 Tomcat(组成员身份只对变更*之后*启动的
进程生效,不影响一个已经在运行的进程)。如果 PAM 登录在此之后仍然失败,请检查
`/var/log/auth.log` 中真正的 `pam_unix(login:auth)` 那一行——这是查看 PAM 本身具体拒绝了什么最直接
的方式,因为 `auth.war` 自己的"Login failed"页面是刻意设计得很笼统的(不提供任何可用于凭据枚举的
线索)。

把它部署在同一个 Tomcat 9 实例中(与 nspawnmgr/Guacamole 一样,后两者占用的是 `/nspawnmgr` 和
`/guacamole`)自己的 `/auth` 上下文路径下,这样它就会提供 `/auth/login`、`/auth/userinfo`、
`/auth/logout`(与下面的 `nspawnmgr.auth.user-id-url` 相匹配):

```bash
sudo cp auth/target/auth.war /opt/tomcat9/webapps/auth.war
```

`tools/scripts/setup-auth-tomcat.sh` 正是这个操作的一个参考,针对本地测试做了适配。`auth` 自己的
登录/登出页面是根据 `request.getContextPath()`(而不是一个写死的路径)来构建它们的内部链接的
(例如"重试"),所以不论它是部署在这里的 `/auth`,还是部署在服务器根路径(例如通过 `jetty:run`
进行本地迭代),这些链接都能正确解析。

### 主机名与共享会话 Cookie

nspawnmgr、`auth` 和 Guacamole **必须都能通过同一个主机名访问**——`auth` 设置的会话 cookie,只有
在两者处于同一个源的 cookie 作用域内时,才对 nspawnmgr 有用。既然现在这三者共享同一个 Tomcat
实例,这在很大程度上是自动满足的(相同主机、相同端口),但仍然要选择一个真实的主机名(不要用
`localhost`,除非你真的只会以 `localhost` 的方式访问这整套东西),在 DNS 或 `/etc/hosts` 中把它
指向这台主机的 IP,并在**`nspawnmgr.host.external-hostname`**(`HOST_EXTERNAL_HOSTNAME`——可以在
`/admin/settings` 上实时编辑,位于 Host 部分下的"外部主机名";在 `.deb` 安装时由
`setup-sudo-account.sh` 自动填充为这台机器的真实主机名,参见第5节)中设置一次。这*不是*那个页面上
紧挨着它的 `nspawnmgr.host.public-address` 设置——两者的区别请参见该字段自身的说明,或参见
[第9节](#9-配置-nspawnmgr)。

除此之外,这个主机名还需要出现在一些普通的 URL 字段中,不会被自动推导——
`nspawnmgr.auth.user-id-url`(`http://<hostname>:8080/auth/userinfo`)、
`nspawnmgr.guacamole.base-url`,以及告知管理员/用户访问的登录页面
(`http://<hostname>:8080/auth/login?returnTo=...`)——但 `/admin/settings` 弥补了这个缺口:
上述每一个 URL 字段都有一个**"刷新主机名/端口/协议"**按钮,会根据上面的外部主机名,加上 Tomcat
部分当前的端口/HTTPS 状态(参见第6节)来重写它,这样修改主机名或端口时,只需要在一个地方输入一次,
然后依次点击其余的按钮即可。

如果你在它前面终止 HTTPS,证书的 CN/SAN 必须与这个主机名匹配——这里的不匹配是"登录成功但
nspawnmgr 仍然显示需要登录页面"这个问题最常见的原因。

**始终通过与 `HOST_EXTERNAL_HOSTNAME`/`AUTH_LOGIN_URL` 相同的主机名来访问 nspawnmgr——不要用
`localhost`、一个 IP 地址,或任何其他别名,即使它解析到的是同一台机器。**`auth.war` 签发的
cookie 没有 `Domain` 属性,所以它的作用域被限定在提供登录页面的那个确切的 host:port 上——具体是
`AUTH_LOGIN_URL` 所指向的那个,而不是你最初输入的那个主机名。nspawnmgr 重定向到登录页面时,总是会
把 `returnTo` 也发回同一个 host:port(不论你最初是从哪个主机名开始的),所以这里的不匹配不会导致
无限循环,但你最终会落在这个规范主机名上,而不是你最初输入的那个——从一开始就始终使用正确的那个
最简单。

### nspawnmgr → auth 的重定向

当 nspawnmgr 无法验证一个会话 cookie 时,它会把浏览器重定向到
`nspawnmgr.auth.login-url`(环境变量 `AUTH_LOGIN_URL`),并带上一个指向用户原本想访问的页面的
`returnTo` 查询参数;`auth.war` 在登录成功后会重定向回那里。如果 `login-url` 留空,nspawnmgr
会转而显示它自己的静态"需要登录"页面,不进行重定向——把 `AUTH_LOGIN_URL` 设置为 `auth` 的
`/auth/login` URL(例如 `http://<hostname>:8080/auth/login`),即可获得完整的自动化流程。

## 9. 配置 nspawnmgr

所有设置都位于 `src/main/resources/application.yml` 中的 `nspawnmgr.*` 之下,每一项都可以通过一个
环境变量覆盖——完整的环境变量形式列表请参见 `site/env/.env.example`,同样内容的 YAML 形式请参见
`dev_env/application-dev_env.example.yml`。几个重要的分组:

- **`nspawnmgr.ssh.*`**——[第3节](#3-具备-sudo-权限的-ssh-账户)中那个具备 sudo 权限的账户
  (`SSH_HOST`/`SSH_PORT`/`SSH_USERNAME`/`SSH_PASSWORD`,主机始终是 `127.0.0.1`),外加
  `SSH_PRIVATE_KEY_PATH`、`SSH_CONNECT_TIMEOUT_MS`、`SSH_STRICT_HOST_KEY_CHECKING`。把
  `SSH_PASSWORD` 留空会把容器创建切换到管理员审批模式,并要求改为设置
  `SSH_PRIVATE_KEY_PATH`(不论哪种方式,SSH 传输认证都需要*某种*凭据)。
- **`nspawnmgr.auth.user-is-admin-json`**——用于外部管理的管理员角色的可选 JsonPath
  ([第3节](#3-具备-sudo-权限的-ssh-账户));留空则使用默认的应用管理模式(第一个登录的用户成为
  管理员,之后可以在 `/admin/users` 管理)。
- **`nspawnmgr.guacamole.*`**——来自[第7节](#7-guacamole)的 `base-url`、
  `admin-username`/`admin-password`、`data-source`、`home`(`GUACAMOLE_HOME`,默认
  `/etc/guacamole`)。
- **`nspawnmgr.auth.*`**——`user-id-url`(针对一个已有的 cookie 向 `auth` 校验)、
  `cookie-name`、`login-url`(第8节所述的重定向目标)、缓存/超时调优、`settings-file`(下面那个
  共享的 auth 设置文件的写入位置——必须与 auth.war 自身的
  `auth.settings-file`/`AUTH_SETTINGS_FILE` 一致,默认为
  `/etc/nspawnmgr/auth-live/auth-settings.properties`)。
- **`nspawnmgr.nspawn.*`**——来自[第2节](#2-主机先决条件)的 `templates-dir`、`machines-dir`、
  `settings-dir`、`privileged-scripts-dir`。
- **`nspawnmgr.dns.upstream-servers`**——逗号分隔的 IP 字面量列表,dnsmasq 会把非 `.internal`
  的查询转发给它们,默认 `1.1.1.1,9.9.9.9`——参见["按名称解析容器"](#按名称解析容器)。
  `hosts-file`/`upstream-servers-file`(`ContainerDnsSyncService` 写入的具体文件)是部署时确定的
  路径,不可实时编辑。
- **`nspawnmgr.host.external-hostname`**(`HOST_EXTERNAL_HOSTNAME`)——来自
  [第8节](#主机名与共享会话-cookie)的共享主机名;这台主机之外的用户所使用的地址,也是
  `/admin/settings` 的 URL"刷新"按钮拉取进每一个 Guacamole/Auth URL 中的值。
- **`nspawnmgr.host.public-address`**(`HOST_PUBLIC_ADDRESS`)——一个不同的、容易与上面那个混淆的
  设置,现在已经不再被 SSH/RDP 路径使用(`guacd` 和 nspawnmgr 自身的就绪检查现在会直接拨号连接一台
  MANAGED 容器的内部 veth 地址——参见[容器网络](#容器网络))。它现在唯一的使用方是网络诊断页面上的
  "HOST_PUBLIC_ADDRESS 不是回环地址"检查;这项检查是否还值得保留,值得后续再看一下,但目前还没有
  重新审视过。`setup-sudo-account.sh` 在安装时仍然会自动检测并填充这台主机的真实地址到这里。
- **`nspawnmgr.crypto.secret-key`**(`APP_SECRET_KEY`)——用
  `openssl rand -base64 32` 生成;用于加密 nspawnmgr 存储的各种密钥(例如它为每个容器管理的
  Guacamole 凭据)。丢失/轮换这个密钥,会导致任何已经用旧密钥加密过的内容失效。
- **`nspawnmgr.provisioning.*`**——`admin-account-name`(当一个新容器的所有者自己的用户名无法使用
  时,nspawnmgr 在该容器内部创建的后备账户——参见下文的"容器用户")、`rdp-password-length`。
- **`CONTAINER_CLI_EXECUTOR=real`**——对于一次真实部署,这必须是 `real`;`fake` 仅用于
  开发/CI,并且不论上面的容器创建模式如何,都完全不会触碰 SSH/sudo/密码。它决定了应用上下文启动时
  连接哪些 Spring bean,所以完全无法在运行时更改——不会出现在 `/admin/settings`
  上,这是刻意的:这是一个部署时的选择,考虑到 `fake` 会做什么(每个容器操作都变成一个悄无声息的
  空操作),把它暴露为一个运行时开关的风险是不值得的。

设置 `SPRING_PROFILES_ACTIVE=prod`——这会激活真正基于 SSH 的执行器,取代本地开发所使用的内存中
伪造实现。

### 实时可编辑设置(`/admin/settings`)

上面这些分组中的一部分也可以在 `/admin/settings`(仅限管理员)实时修改:
`guacamole.base-url`/`data-source`、`host.external-hostname`/`public-address`、每一个
`auth.*` 字段(包括 `http-timeout-ms`)、`provisioning.admin-account-name`/`rdp-password-length`、
`nspawnmgr.ssh.*`、`nspawnmgr.nspawn.*`,以及 `nspawnmgr.dns.upstream-servers`。这些设置对之后
每一个新的请求/分配都会立即生效——`SettingsService` 持有一份内存中的快照,一旦保存了变更就立即刷新,
不是每次请求都读一次数据库。有一个例外,页面上本身也有说明:

- **`nspawnmgr.nspawn.privileged-scripts-dir`** 和其他内容一样会立即生效,但如果只改这一项、而*不*
  同步更新 `/etc/sudoers.d/nspawnmgr_exec` 中写死的路径,会破坏**所有**特权操作(容器启动/停止、
  出站访问同步、下面的"重启 Tomcat")——sudo 会安全失败,直接拒绝这个新路径,而不是照着这个设置去
  执行。这一项目前没有实时校验(它是一个本地路径,保存时可能甚至还没有被创建出来)——页面上只有一条
  警告提示。
- **`nspawnmgr.dns.upstream-servers`** 会立即在 `SettingsService` 自己的快照中生效,与其他设置
  一样,但要真正影响到正在运行的 dnsmasq 还差一步:`ContainerDnsSyncService` 只会在它自己约 15
  秒一次的轮询中才会拾取新值、重写
  `/etc/dnsmasq.d/nspawnmgr-upstream.conf` 并重启 dnsmasq——具体为什么这需要一次完整的
  `systemctl restart`,而不只是一次重新加载,请参见["按名称解析容器"](#按名称解析容器)。

**其余的一切都保持静态/环境变量/仅重启生效**,这是刻意的:
`nspawnmgr.crypto.secret-key`/`nspawnmgr.guacamole.admin-username`/`admin-password`(密钥,而且
在运行时轮换加密密钥会导致任何已经用旧密钥加密过的内容失效),以及
`CONTAINER_CLI_EXECUTOR`(见上文)。Host 根本不是一个静态设置——它们完全由管理员通过每个 Host 自己
的详情页面以及 `/admin/hosts/new` 来管理(参见上文的"Host:由管理员管理的外部机器")。

每一次变更在被接受之前都会经过校验:
- **Guacamole 基础 URL、auth 用户 ID URL、auth 登录 URL**:一次实时的 HTTP 可达性探测(任何响应,
  即使是 404,都算作可达——这只能证明这个 URL 能解析到某个正在监听的东西,不能证明认证本身会成功)。
- **五个 JsonPath 字段**:必须能编译为有效的 JsonPath 表达式。
- **Host 公网地址**:仅做格式检查(主机名/IP 语法)——刻意*不*进行探测,因为一个公网地址往往只能从
  这台主机之外才能访问到;自我探测它证明不了什么。
- Cookie 名称、缓存 TTL、管理员账户名,以及 RDP 密码长度会进行基本的格式/范围检查。
- **`dns.upstream-servers`**:必须是一个逗号分隔的 IP 字面量列表(IPv4 或
  IPv6)——一个主机名会被拒绝,因为 dnsmasq 自身的 `server=` 指令需要一个本身就已经可以在没有任何
  DNS 服务器的情况下被解析的值(这正是 dnsmasq 自己用来解析其他一切内容的方式)。
- **`ssh.*`**:如果提交的变更中包含任意一个 SSH 字段,在这次变更被接受之前,会用*最终生效*的设置
  真正打开一次 SSH 连接(仅传输层登录——不执行命令,所以这不依赖于 NOPASSWD sudoers 授权是否正确)。
  设置页面总是会把所有字段一起重新提交(与这里的其他每个部分一样),所以实际上从界面上每次保存都会
  触发这个检查——与已有的 Guacamole/auth URL 可达性探测方式相同。如果直接调用 API、提交一个省略了
  所有 `ssh.*` 键的部分负载,则会跳过这个检查。

#### Auth 部分(条件性显示,取决于是否检测到 auth.war)

如果 auth.war 看起来是可达的(对 `auth.login-url` 的一次实时探测),`/admin/settings` 还会显示一个
针对 auth.war**自身**后端配置的区域:`auth.backend`(`pam`/`smb`)、SMB
服务器/域,以及来自[第8节](#8-auth登录后端)的必需组/必需共享把关设置——目前这些内容都只存在于
auth.war 的 `web.xml` context-params/系统属性中,在部署时固定。

保存这个区域(连同上面的 cookie 名称一起,auth.war 也需要与此保持一致——它才是真正设置这个 cookie
的一方)会把它们写入 `nspawnmgr.auth.settings-file` 所指向的共享属性文件。`AuthConfig` 在每次请求
时都会**优先**检查这个文件,先于它自己的 context-params/系统属性——所以这里的一次保存,会在
auth.war 的下一次请求时立即生效,两个 web 应用都不需要重启。这里留空/未设置的值,单纯就是"不覆盖";
auth.war 会像这个功能存在之前一样,回退到它自己 `web.xml`/系统属性中的默认值。这个文件写入是尽力
而为的:如果失败了(例如一次手动安装跳过了[第5节](#5-安装-nspawnmgr)中
`/etc/nspawnmgr/auth-live/` 的设置),数据库保存仍然会成功,并会记录一条警告——它不会阻塞设置更新
的其余部分。

#### Guacamole 部分(条件性显示)

如果 Guacamole 看起来是可达的(对 `guacamole.base-url` 的一次实时探测),`/admin/settings`
还会显示一个针对 `guacamole.properties`(位于 `nspawnmgr.guacamole.home`)的结构化编辑器:
`guacd-hostname`/`guacd-port`/`guacd-ssl` 各自的独立字段,加上一个数据库类型选择器
(MySQL/MariaDB 或 PostgreSQL),选择后会展开对应的 `guacamole-auth-jdbc` 扩展所支持的每一个
字段——连接、SSL/TLS、密码策略、每连接并发限制、外部认证集成,以及访问窗口强制执行。字段标签和帮助
文本直接来自
[Apache Guacamole 官方手册](https://guacamole.apache.org/doc/gug/configuring-guacamole.html)
([MySQL](https://guacamole.apache.org/doc/gug/mysql-auth.html) /
[PostgreSQL](https://guacamole.apache.org/doc/gug/postgresql-auth.html) 认证扩展页面),不是
本地自行编写的。

加载这个页面会读取现有文件并预先填充每个字段,包括任何已经设置好的密码(以标准的、遮罩显示的
`<input type="password">` 渲染,与本应用中其他任何地方修改一个已保存凭据的方式相同——不会在屏幕上
以明文显示,但请注意这是一个刻意的设计选择:与 `/admin/settings` 其余部分把密钥完全排除在实时编辑
界面之外不同,这个编辑器存在的全部意义就是让管理员无需 SSH 登录,就能查看和调整一份已有的
Guacamole 数据库配置)。保存只会触碰上面文档记录的那些键:它会清空你*没有*选择的那个数据库扩展的
键(这样文件里就不会积累上一次选择留下的过期配置),并保留文件中已有的任何其他键不受影响(例如某个
手动添加的扩展自己的设置)。保存**不会**重启 Tomcat——在你自己重启之前(`sudo systemctl restart
tomcat9`),Guacamole 不会看到这次变更。

#### 设置报告

"下载设置报告"会生成一个纯文本文件,包含页面上的每一项设置(加上数据库向导持久化的
`DB_URL`/`DB_USERNAME`/`DB_VENDOR`,以及 Guacamole 结构化编辑器当前的文件值),按与页面本身相同的
方式分组。每一个像密码的值——`ssh.password`、`DB_PASSWORD`、任何 Guacamole 的
`*-password` 键——都会被替换为字面的 `********`:这份报告只确认*某个值已经设置*,从不透露它具体是
什么。

#### 重启 Tomcat

通过与其他常规特权操作相同的、具备 sudo 权限的 SSH 账户和 NOPASSWD sudoers
授权(参见[第3节](#3-具备-sudo-权限的-ssh-账户)),触发
`sudo systemctl restart --no-block tomcat9`——`.deb` 会自动提供所需的封装脚本
(`/usr/lib/nspawnmgr/privileged/nspawnmgr-restart-tomcat.sh`)和 sudoers 条目。一次手动
(非 `.deb`)安装需要手动添加这两者:把脚本从
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-restart-tomcat.sh` 复制到
`nspawn.privileged-scripts-dir`,然后把它的路径加入
`/etc/sudoers.d/nspawnmgr_exec` 中的 `NSPAWNMGR_NOPASSWD` 别名(在信任它之前先用
`visudo -cf` 校验)。

这次重启是异步触发的(`--no-block` 会把这个 systemd 任务加入队列,几乎立即返回),而不是被
等待——等待也没有意义,因为发起这次重启请求的这个请求本身,正是由即将下线的这个 Tomcat 实例提供
服务的。点击按钮并确认之后,页面会等待 5 秒,在客户端清除会话 cookie,然后重新加载——一旦(此时已经
重启完成的)应用发现 cookie 缺失,就会回到登录页面,与任何其他过期会话的处理方式相同。

## 10. 验证部署

**在一次 `.deb` 安装中**(自托管——[第1节](#1-架构概述)):下面的 `<hostname>:<port>` 指的是
`postinst` 在安装期间打印出来的那个端口(除非已被占用,否则是 8080),而
`machinectl list`/查看日志之类的命令需要用
`sudo machinectl shell nspawnmgr <command>`——Tomcat、`guacd`,以及两个 WAR 的日志全都存在于那台
机器内部,不在主机上。在一次手动的、选项 B(Tomcat 在主机上)的安装中,下面的一切都直接运行在
主机上,与以往一样。

1. 确认自托管的 `nspawnmgr` 机器已经启动:主机上的 `sudo machinectl list` 应该显示它处于
   `running` 状态(一旦你完成了第4节,还应该看到它的数据库机器)。在它内部,`guacd` 和
   Tomcat(`nspawnmgr.war` + `guacamole.war` + `auth.war`)应该都在运行。
2. 直接访问 `http://<hostname>:<port>/auth/login`,确认你可以用第4节向导中创建的初始账户登录
   (如果配置了的话,还要确认一个不属于 `auth.required-group`/`smb.required-share` 的账户会被正确
   拒绝)。
3. 在没有任何 cookie 的情况下访问 `http://<hostname>:<port>/nspawnmgr/`——你应该被重定向到
   `auth` 登录页面,登录后再被带回 nspawnmgr。此时 `nspawnmgr`/数据库机器应该已经作为普通容器出现
   在容器列表中了——向导会直接注册它们,不需要先登录。
4. 通过 nspawnmgr 的界面创建一个新容器,确认它确实启动了(主机上的
   `sudo machinectl list` 应该能看到它),并且为它出现了一个 Guacamole 连接。
5. 如果上面任何一步失败了,可以查看 nspawnmgr 自己的"查看日志"页面(前提是它至少已经启动到能提供
   页面的程度),或者用 `sudo machinectl shell nspawnmgr journalctl -u tomcat9` 查看更底层的
   故障信息——大多数首次部署的问题都是主机名/cookie 不匹配(第8节),或者 sudo 账户(第3节)本身
   没有正确配置 sudo/SSH 访问权限。

## 11. 日常运维

- **日志**:对于这一个 Tomcat 实例(nspawnmgr、Guacamole 和 auth 都在这里记录日志),是
  `<tomcat-dir>/logs/catalina.out.<date>.log`;对于 Guacamole 的代理守护进程,是
  `journalctl -u guacd`——在一次 `.deb`(自托管)安装中,两者都存在于 `nspawnmgr` 机器*内部*
  (`sudo machinectl shell nspawnmgr <command>`),不在主机上。`.deb` 通过 `tomcat9.service` 的
  `ExecStart`,把 Tomcat 自身的标准输出/标准错误接到了 `rotatelogs`(`apache2-utils`)上,每天
  生成一个新的带日期的文件——与一次普通的 `catalina.sh start` 不同,这个软件包的
  `tomcat9.service` 直接运行 `catalina.sh run`,这本身从不会产生一个不带日期的
  `catalina.out`(那只有你交互式运行 Tomcat 时才会看到,例如开发环境)。每一个已登录用户都可以在
  nspawnmgr 自己的"查看日志"页面查看最近 100 行以及完整的当前日志;管理员还可以在那里浏览和删除
  单独的、已轮转出去的历史日志。
- **重启**:修改任何 `-D`/环境变量配置之后都要重启 Tomcat——没有任何一项是热重载的,并且由于三个
  web 应用共享同一个实例,重启它就是把三者一起重启。只有在修改了 `guacamole.properties` 中的
  `guacd-hostname`/`guacd-port` 之后,才只需要重启 `guacd`。
- **备份**:请分别备份 nspawnmgr 自己的数据库(容器/用户元数据)、Guacamole 自己的数据库
  (连接历史/参数),以及 `/var/lib/machines`(容器根文件系统)——它们是相互独立的存储,除了
  nspawnmgr 在应用层面维护的一致性之外,彼此之间没有强制的引用完整性。
- **轮换 `APP_SECRET_KEY`**:目前没有内置的重新加密工具;请把这当作一个应急、需要提前规划的操作,
  不要在一个正在运行的系统上随意更改。
- **待处理的容器请求**(仅限管理员审批模式):会出现在 `/requests`。`DENIED` 目前是一个终态——
  没有重新提交的功能,发起请求的用户必须从头重新创建容器。
