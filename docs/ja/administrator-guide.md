# nspawnmgr 管理者ガイド

このガイドでは、nspawnmgrの実運用環境をゼロから構築する手順を説明します：Linuxホストと
`systemd-nspawn`、データベース、Tomcat、Apache Guacamole、`auth`ログインアプリ、そしてnspawnmgr
自体です。単一のDebian/Ubuntu系Linuxホストですべてを動かすことを前提としています。これはこの
プロジェクト自体がビルド・テストされている構成です。別のディストリビューションを使う場合は
パス/パッケージ名を適宜読み替えてください。

ローカル開発ループ（フェイク、実コンテナなし、実Guacamoleなし）については、代わりに
`site/env/README.md`と`dev_env/README.md`を参照してください — このガイドは実運用デプロイに
ついてのものです。

## 1. アーキテクチャ概要

**nspawnmgrは自分自身のsystemd-nspawnマシンの1つから実行されます** — `nspawnmgr`という名前の
自己ホスト型Debianコンテナで、管理者がアプリに触れる前に`.deb`の`postinst`
（`nspawnmgr-bootstrap-app-machine.sh`）によって自動的に作成されます。ベアホスト側に残るのは
小さな固定セットのみです：

| ホスト側に残るもの | 理由 |
|---|---|
| `nspawnmgr_exec`（sudo権限を持つSSHアカウント、[§3](#3-sudo権限を持つsshアカウント)） | コンテナの作成/管理にはベアホスト上での本物のrootが必要 — これを持つのはこのアカウントだけ |
| テンプレートとパッケージ（`/var/lib/nspawnmgr/templates`、管理者用パッケージキャッシュ） | すべてのコンテナ（nspawnmgr自身のものを含む）が構築される共有のホスト側ストレージ |
| `nspawnbr0`（共有ブリッジ）とdnsmasq | nspawnmgr自身を含む、すべてのコンテナが接続するネットワーキング |

それ以外のすべて — Tomcat、4つのWAR（`nspawnmgr.war`、`auth.war`、`guacamole.war`、
`ROOT.war`）、そして`guacd` — は`nspawnmgr`マシンの**内部**で動作し、そこの1つのTomcat 9
インスタンスにまとめられ、それぞれ以前とまったく同じ独自のコンテキストパス（`/nspawnmgr`、
`/auth`、`/guacamole`、そして`ROOT.war`用の`/`）を持ちます — 変わったのはそのTomcatインスタンスが
*どこで*動くかだけで、4つのWARが互いにどう配置されるかではありません。nspawnmgr自体がなぜ
Boot 2.7/Tomcat 9に固定されているのか（Guacamole自身のwebappがJakarta EE/Tomcat 10+では
無改造で動かせないため）については、ルートの`pom.xml`の先頭にあるコメントを、同じ理由が
`auth`にも当てはまることについては`auth/pom.xml`の先頭のコメントを参照してください。

`nspawnmgr`マシンにはホストネットワークへのアクセスがない（他のすべてのコンテナと同様、
`nspawnbr0`への普通のvethのみ）ため、`postinst`は空いているホストポート（8080、または
その次の空いているポート — どれになったかは表示されます）を選び、そのマシン自身の`:8080`へ
`.nspawn`ファイルの`Port=`行で直接転送します。これは[カスタムポートマッピング](#カスタムポートマッピングとアウトバウンドアクセス)が
通常のコンテナに使うのと同じ仕組みです。そのため`http://<このホスト>:<そのポート>/`に
ブラウザでアクセスすれば、これまでどおりnspawnmgrに到達できます — セルフホスティングは
ブラウザ側からは見えません。

`auth.war`のPAMバックエンド（デフォルト — [§8](#8-authログインバックエンド)を参照）は、自分自身の
JVMが動くホストのローカルOSアカウントに対して認証を行います。`auth.war`が今は`nspawnmgr`
マシンの内部で動くようになったため、これはつまりそのアカウントが — ベアホストのものではなく
— [初回起動セットアップウィザード](#初回起動セットアップウィザード)の中で作成されたものである
ことを意味し、それを成立させるための特別なバックエンドコードや設定は何も必要ありません。

データベースも同様にセルフホスト型です：初回起動セットアップウィザードは、既存のサーバーに
接続するのではなく、独自のDebianデータベースマシンをプロビジョニングします（[§4](#4-データベース)
参照）。`nspawnmgr`マシンとそのデータベースマシンはどちらも、初回起動ウィザードが完了した
時点でnspawnmgr自身のコンテナ一覧に、通常の目に見えるコンテナとして表示されます —
[§4](#4-データベース)のこの点についての注記を参照してください。どちらも
[ホスト自体の起動時に自動起動する](#ホスト起動時の自動起動)ように設定されており、`nspawnmgr`は
自分のデータベースマシンがすでに起動していることを要求するよう設定されています —
そうしないと、ホストの再起動によって`nspawnmgr`マシンがデータベースマシンより先に起動して
しまい、誰かが気づいて手動でもう一方のマシンを起動するまで、到達可能なデータベースなしで
動き続けてしまう可能性があります。

nspawnmgr自身は`machinectl`/`systemd-run`を直接実行することはありません — Tomcatが実行される
アカウントには、Tomcat自体がどこで動いているとしてもsudo権限がありません。代わりに
nspawnmgrはベアホスト上の**別の、sudo権限を持つ`nspawnmgr_exec`アカウント**にSSH接続し、
そこでrootとして権限を要する処理を実行します — 日常的な操作（コンテナの起動/停止/削除、
ファイアウォールの同期）はパスワードなしで行われ、よりリスクの高い、作成時のみの処理
（テンプレート作成者由来のコンテンツを新しいコンテナ内でrootとして実行する、またはまったく
新しいマシンをプロビジョニングする）だけがパスワードを必要とします。このパスワードは保存済み
の設定、またはリクエストごとの管理者承認のどちらかから取得されます。パッケージ経由の
インストールでは、このSSH接続は`127.0.0.1`ではなく`nspawnbr0`自身の固定アドレス
（`10.100.0.1`）を対象とします。nspawnmgrは自分自身と話すのではなく、自分自身のマシンの内側
からホストへ*外向きに*到達しようとしているためです — これは`nspawnmgr-bootstrap-app-machine.sh`
によって自動的にセットアップされ、手動で設定する必要はありません。このアカウントの
セットアップは、以下の手順の中でも特に重要で見落としやすいものの1つです
（[§3](#3-sudo権限を持つsshアカウント)）。

## 2. ホストの前提条件

コンテナを実行するLinuxホスト上で：

```bash
sudo apt update
sudo apt install -y systemd-container openssh-server
```

`systemd-container`は`machinectl`、`systemd-nspawn`、`systemd-run`を提供します — これには
`machinectl import-tar`も含まれ、nspawnmgrはこれを使ってコンテナテンプレートを新しいマシンへ
複製します（`systemd-importd`と通信します。これは`machinectl start`のための`systemd-machined`と
同じくソケットアクティベーションされているため、追加のセットアップなしでそのまま動作する
はずです）。基本動作を確認してください：

```bash
machinectl list-images   # should run without error, even with an empty list
```

nspawnmgrは、次の2つのディレクトリが存在し、sudo権限を持つアカウントから書き込み可能である
ことを前提としています（`systemd-nspawn`/`machinectl`が初めて使われる際に自動的に作成されますが、
確認しておく価値はあります）：

- `/var/lib/machines` — コンテナのルートファイルシステムが置かれる場所（`NSPAWN_MACHINES_DIR`）
- `/etc/systemd/nspawn` — コンテナごとの`.nspawn`設定ファイルが置かれる場所
  （`NSPAWN_SETTINGS_DIR`）

これらは**実在する、固定のシステムパス**です — `machinectl`/`systemd-nspawn`はnspawnmgr自身の
設定が何を言おうと、決して他の場所を見に行きません。これらをサンドボックス化しようとしない
でください。

### データベース（2つ、それぞれ独立 — nspawnmgr用とGuacamole用）

**2つの独立したデータベース**を、同じMySQL/MariaDBまたはPostgreSQLサーバー上に計画してください：
nspawnmgr自身のusers/containers/settings/templatesスキーマと、Guacamole自身の
users/connections/permissionsスキーマ（Guacamoleの`guacamole-auth-jdbc`拡張によって別途
管理されます）。**MySQL/MariaDBまたはPostgreSQLのみ — H2は選択肢にありません。**
[§4](#4-データベース)を参照してください — 初回起動セットアップウィザードが、決め打ちの
固定名（`nspawnmgr`/`guacamole`）で両方のデータベースを作成してくれるため、事前に手動で
準備しておくものは何もありません。

### コンテナテンプレート（ベースとなるルートファイルシステム）

nspawnmgrは「テンプレート」を`machinectl import-tar`経由で`/var/lib/machines`に複製すること
で新しいコンテナをプロビジョニングします。テンプレート自体は`TEMPLATES_DIR`（デフォルトは
`/var/lib/nspawnmgr/templates`）配下に、バックエンドごとに1つのサブディレクトリ — `nspawn/`、
`podman/`、`qemu/` — として置かれます（他の2つのバックエンド自身のテンプレート形式とその
生成方法については下記の[「Podman：ポッド」](#podmanポッド)と
[「QEMU：仮想マシン」](#qemu仮想マシン)を参照してください — このセクションでは具体的にnspawnの
`<name>.tar.gz`ファイルについて説明します：ルートファイルシステムの単なるgzip圧縮tarで、
`machinectl import-tar`自体が消費するものそのものです）。少なくとも1つ、実際に起動可能な
ものを自分で用意する必要があります — nspawnmgrはこれをダウンロードしたりビルドしたりは
しません。ただし1つ例外があります：`/admin/templates`には3つの独立した
**「X-minimalをセットアップ」**ボタンがあります — **debian-minimal**（APT）、
**fedora-minimal**（DNF）、**arch-minimal**（PACMAN） — それぞれ対応するフレーバーの
テンプレートがまだ存在しない間だけ表示されます（1つセットアップしても他が隠れることは
ありません。1つでも全部でもセットアップできます）。それぞれ実際のminirootfs
（チェックサム検証済み）をimages.linuxcontainers.orgからダウンロードし、SSHサーバーを
インストール・有効化し、`TEMPLATES_DIR/nspawn/<flavor>-minimal.tar.gz`としてパッケージ化し、
「SSHプリインストール済み」フラグを立てて登録します — ワンクリックで本物の動作する
テンプレートができあがります。このフラグ（手作業で作成したテンプレートにも設定可能、
その編集フォームを参照）は、そのイメージにすでにSSHがインストール・有効化済みであることを
コンテナ作成処理に伝え、他のすべてのテンプレートで必要となる、本来は冗長な
ダウンロード/インストール/有効化のステップをスキップします。これは汎用的なテンプレート
管理ツールではありません：カスタム名向けの同等のボタンは存在せず、各ボタンはそれぞれ
固有のフレーバーのテンプレートが存在するようになった時点で消えます（他のテンプレートが
何であるかには関係なく）。作成時のみに必要な他のすべてのものと同じsudo要件です（§3） —
管理者承認モードでは、インラインでsudoパスワードの入力を求められます。それぞれが正確に
何をするかについては
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-{debian,fedora,arch}-template.sh`
を参照してください — **実コンテナに対して確認されているのはDebianのものだけです**。他の2つの
検証状況については下記の
[「FedoraとArchのテンプレート：検証状況」](#fedoraとarchのテンプレート検証状況)を、また3つの
スクリプトすべてが今採用しているデュアルパス（ホストネイティブ vs. chroot）アプローチに
ついても同項を参照してください。リポジトリ自身の
`site/templates/nspawn/{debian-minimal,fedora-minimal,arch-minimal,alpine-minimal}`は
*別物*です — ローカルの開発モードテストにのみ使われる小さなプレースホルダーディレクトリ
（tarballですらありません）です（`site/templates/README.md`参照） —
**これらを実際のテンプレートとして使わないでください**、起動可能ではありません。

3つの中にAlpineフレーバーが意図的に含まれていません：Alpine公式のminirootfsには
systemd/D-Busがまったく存在せず（OpenRCを使用）、nspawnmgrがコンテナ内で実行する
すべてのコマンドは`systemd-run --machine=`を経由するため、コンテナ自体がsystemdを実行して
いる必要があります — Alpineベースのコンテナは「Failed to connect to bus」で恒久的に失敗し、
リトライする価値のある一時的な起動レースではありません。実際にAlpineをサポートするには、
コンテナ内でPID 1としてsystemdをインストールして動作させる必要があり、これはAlpineでは
標準的ではなく、ここではテストされていません。

#### FedoraとArchのテンプレート：検証状況

**debian-minimalは、3つの「X-minimalをセットアップ」ボタンの中で唯一、実コンテナに対して
確認されているものです** — このプロジェクトの過程で、実際に複数回作成・起動されています。
**fedora-minimal**と**arch-minimal**は、特に未検証のままです：実際のFedora/Archホストは
存在し、このプロジェクトの他の箇所（上記のRPM/Archパッケージインストールのセクション参照）
で広く使われていますが、`nspawnmgr-create-fedora-template.sh`/
`nspawnmgr-create-arch-template.sh` — この2つの特定の管理UIボタンが呼び出すスクリプト —
は実際のsystemd-nspawnコンテナに対して一度も試されたことがありません。どちらかを試して
みる場合は、何が壊れたか報告してください — 具体的な既知のリスク領域を、影響を受けやすい
順にいくつか挙げます：

- **3つのベイクスクリプト（Debian、Fedora、Arch）はすべて、いずれか1つのディストリを前提と
  するのではなく、HOST自身のディストリを検出し、2つのインストールパスのうち一方を選択
  します**。各スクリプトは、自分自身がターゲットとするパッケージマネージャー用に
  `command -v apt-get`/`dnf`/`pacman`をチェックします：ホストに一致するものがあれば、抽出済み
  のrootfsを対象とした通常の**ホスト側プロセス**としてそのツールを実行します（aptの
  `-o Dir=`/`-o DPkg::Options::=--root=`の組み合わせ、`dnf --installroot=`、`pacman --root=`）。
  ホストに一致するパッケージマネージャーがまったくない場合（例えば、Debianホストにデプロイ
  されたnspawnmgrがFedoraやArchのテンプレートをベイクする場合、あるいはその逆）、スクリプトは
  代わりに**新しく展開されたrootfsに`chroot`し、そのイメージ自身にバンドルされたそのツールの
  コピーを使います** — `/etc/resolv.conf`をコピーして持ち込み（chrootはホストのネットワーク
  設定を共有しません）、chroot化されたインストールが実行される前に`/dev`/`/proc`/`/sys`/`/run`を
  バインドマウントし（`/run`のバインドマウントは特に、chroot内で`systemd-resolved`のNSS
  モジュールにDNS解決のためにアクセス可能にします — これがないと、正しい`/etc/resolv.conf`が
  あっても名前解決が失敗することがあります）、tarballがパッケージ化される直前に再び
  アンマウントします — これは`pacstrap`/`arch-chroot`/`debootstrap`自身のchrootステージが
  使うのと同じ手法です。Debianスクリプトのホスト側ブランチ（Debian-on-Debian）だけが実際に
  実コンテナに対して実行されたことがあります。Debianスクリプトのchrootフォールバックと、
  Fedora/Archスクリプトの両方のブランチは、仕様通りに作られてはいますが未検証です —
  これらの特定のテンプレートベイクスクリプトは、実際のFedora/Archホストがこのプロジェクトの
  他の場所で存在し使われているにもかかわらず、実際に本番で実行されたことはありません。
- **arch-minimalは3つの中で最も推測に基づいています。** 既知のリスク領域：(1) ダウンロード
  されるイメージの`/etc/pacman.d/mirrorlist`は、Arch自身の慣例によりすべてのミラーが
  コメントアウトされた状態で出荷されます — スクリプトは`geo.mirror.pkgbuild.com`
  （Arch公式のGeoIPリダイレクター）を明示的に書き込みます；(2) パッケージ署名検証には
  このスクリプトがセットアップしないポピュレート済みのキーリングが必要です（本物の
  `pacstrap`は`pacman-key --init`/`--populate`経由でこれを行います） — テストする方法がない
  まま盲目的にそれを試みる代わりに、スクリプトはこのブートストラップインストールについて
  署名チェックを無効化します（ターゲットの`pacman.conf`で`SigLevel = Never`）。これは
  クイックスタートの開発/テスト用テンプレートとしては妥当ではあるものの、知っておくべき
  本物のセキュリティ上のトレードオフです；(3) chrootブランチはさらに`pacman.conf`で
  `CheckSpace`も無効化します — pacmanのディスク容量チェックは`/proc/self/mountinfo`経由で
  キャッシュディレクトリをマウントポイントに解決しますが、chroot内ではこれが
  chrootの再マップされた`/`ではなくホスト自身の絶対パスを反映し続けるため、実際の空き容量
  にかかわらず紛らわしい「ディスク容量不足」でチェックが失敗します（既知のpacman-in-chroot
  の制限）；(4) `pacman.conf`にはさらに`DisableSandbox`も追加されます —
  pacman自身のLandlockベースのダウンロードサンドボックス化（専用の非特権`alpm`ユーザーへの
  切り替えを含む）は、コンテナが実際に起動して`pacman`を実行すると
  `systemd-nspawn`のデフォルトのseccompフィルターによってブロックされます（このスクリプト
  自身のホスト側`chroot`にはseccomp制限がまったくないのとは対照的です） — 実際に稼働している
  コンテナ内でのすべての`pacman`呼び出しは、これがなければまったく動作しません。単に
  このスクリプト自身のベイクステップだけの話ではありません。
- **RDPは`arch-minimal`ではまったく利用できません。** 実際に確認済み：`xrdp`/`xorgxrdp`は
  Arch公式リポジトリから削除されています（`pacman -Ss xrdp`は、新しく同期され完全に
  populateされたミラー上でもどちらも見つけられません — 古いキャッシュや誤ったミラーの
  問題ではありません）、そしてこのアプリにはフォールバックできるAURサポートがありません。
  `arch-minimal`はデフォルトでRDP状態自身を「非対応」に設定します（Templates管理ページの
  「RDP」セレクター参照）。これが実際にNew Nspawnフォームの「Enable RDP」オプションを
  無効化しているものです — 将来のArchリリースでパッケージが復活した場合、またはテンプレート
  自身のインストールコマンドを動作するものに手動編集した場合（例：`extra`にまだ残っている
  KDE自身の`krdp`。ただしKDE/Plasma専用です）にのみ、手動でこれを元に戻してください。
- **すべてのFedoraコンテナは、SSHでまったく到達可能になるために`sshd`のアカウントフェーズの
  PAMチェックをバイパスする必要があります。** 実際に起動されたFedoraコンテナへのすべての
  SSH公開鍵ログイン試行（43と44の両方で確認済み — リリース固有ではありません）は
  `Access denied for user <account> by PAM account configuration [preauth]`
  （`pam_unix`のアカウントフェーズ、`pam_acct_mgmt`が`PAM_AUTHINFO_UNAVAIL`を返す）で拒否
  されます — アカウント、パスワード、`authorized_keys`はすべて本当に正しいものです。
  `unix_chkpwd`自体（`pam_unix`が`/etc/shadow`を安全に読むために呼び出すsetuidヘルパー）は
  「This binary is not designed for running in this way」で実行を拒否します —
  `systemd-nspawn`コンテナ内で実行されることを許容しないFedoraの現行`shadow-utils`の中の
  何らかの呼び出し元正当性チェックです。`sshd_config`の`UsePAM no`は**回避策になりません**
  — 実際に確認済みで、sshd自身の特権を持つモニタープロセスは、このビルドではそれでも
  `do_pam_account`を呼び出します（sshd自身が`'UsePAM no' is not supported in this build`と
  警告します）。実際に効く修正は次のとおりです：スクリプトは`sshd`自身のアカウントフェーズを、
  `password-auth`の`pam_unix.so`ではなく（常に成功する）`pam_permit.so`に、`/etc/pam.d/sshd`
  内でのみ向けます — システム全体のPAM変更ではありません。これはPAMの*アカウント*フェーズの
  チェック（有効期限、`nologin`など）をSSHについてのみ除去します。実際の識別チェック
  （公開鍵の検証）はすでにこのフェーズが実行される前に独立して成功しているため、これはこの
  使い捨てのプロビジョニング済み管理者アカウントに対する、狭い範囲の意図的なトレードオフ
  です。Fedora 43で実際に動作確認済み。リリースは（新しい44ではなく）43に固定されています。
  これは単にエンドツーエンドで検証された正確な組み合わせがそれだからであり、44がそれ以外の
  点で劣っているからではありません。
- **すべてのFedoraとArchのコンテナのSSHプロンプトが、リテラルなエスケープシーケンスの
  テキストで埋め尽くされていました** —
  `start=<uuid>;machineid=<uuid>;user=...;hostname=...;bootid=<uuid>;pid=...;type=shell;cwd=...`
  というテキストが、普通の`[user@host ~]$`の代わりに表示されていました。根本原因
  （Fedoraで実際に確認済み。Archも同じ症状を示し、同じ根本原因を共有しています。これは
  Fedora固有の癖ではなく、単にこれを出荷するほど新しいsystemdを持つディストリの問題で、
  両方とも該当します）：systemd 257以降は
  `/usr/lib/systemd/profile.d/80-systemd-osc-context.sh`（`systemd-tmpfiles`によって
  `/etc/profile.d/`にシンボリックリンクされます）を出荷し、これは毎回のプロンプトで
  OSC 3008「Hierarchical Context Signalling」エスケープシーケンスを発行します。Guacamole
  自身のターミナルエミュレーターはこれを認識/除去しないため、リテラルテキストとして
  印字されます。このスクリプトは`$TERM`が未設定または`dumb`のときにのみ自身をスキップします
  （自身のヘッダーコメント参照）が、Guacamole自身のSSHクライアントは実際の`$TERM`を報告する
  ため、常に発動します。両方のベイクスクリプトで、文書化された方法（スクリプト自身のヘッダー
  コメントがこの正確な手順を示しています）で無効化しました：`/etc/profile.d/`シンボリック
  リンクを削除し、それを再生成する`tmpfiles.d`のスニペットをマスクします。
- **FedoraコンテナへのXfceデスクトップマネージャーのインストールが完全に失敗していました**
  — `dnf group install -y "Xfce Desktop"`が`No match for argument: Xfce Desktop`で
  エラーになりました。実際に確認済み：GNOME/KDEとは異なり、「Xfce Desktop」は現在の
  Fedoraではまったくcompsグループではありません（`dnf group list --available`には
  リストされません） — Fedoraは代わりに、デスクトップ全体を引き込む単なる名前付き
  パッケージ`xfce4`を出荷しています。単純な`dnf install -y xfce4`に切り替えました。
  これはXfce-on-DNFを事前フェッチ可能にもします（上記の「パッケージのインストール：
  先にダウンロードする」参照） — GNOME/KDE自身のcompsグループインストールとは異なり、
  これらは事前フェッチできず、コンテナ自身のネットワーク/DNSが動作する必要があります。
  ついでに、この同じ事前フェッチの仕組みをAPT専用からAPT/DNF/PACMAN全般に広げました
  （基盤となるダウンロードスクリプトはすでに3つすべてをサポートしていましたが、それらを
  使うかどうかを決めるゲートだけがAPT専用のままでした） — SSH/RDP/VNCのパッケージ名も
  今ではパッケージマネージャーごとに解決されます（例：ArchのSSHパッケージは
  `openssh-server`ではなく`openssh`です；そのRDPインストールにはさらに`xorgxrdp`が必要です）。
- **その事前フェッチの拡張により、Fedora/Archのコンテナ作成が完全に壊れました** —
  `Failed to download DNF packages [openssh-server] ... dnf: not found`、そしてPACMANでも
  同一の失敗。両方とも実際に確認済み。根本原因：`nspawnmgr-download-packages-dnf.sh`/
  `-pacman.sh`（および管理者のPackagesアップロードフローが使う、シミュレートインストール
  用の兄弟スクリプト）は`dnf`/`pacman`を*ホスト*上で直接実行していました（コンテナの
  rootfsを指す`--installroot=`/`--root=`）— APTについては動作します。このプロジェクトの
  `.deb`はDebian/Ubuntuホストのみを対象としており、常に`apt-get`があるためです。しかし
  `dnf`も`pacman`も、そのようなホスト自身の`PATH`上に存在することはありません。
  テンプレートの*ベイク*（まだ起動していないrootfsへのホスト側`chroot`にフォールバック
  できる）とは異なり、すでに稼働中のコンテナは同じようには安全にchrootできません — 修正
  では代わりに、実際のインストールステップがすでに使っているのと同じ非対話式の
  in-container実行プリミティブである`systemd-run --machine=`経由で、コンテナ*自身の内側で*
  `dnf`/`pacman`を実行します — ダウンロードのみなので、インストール済みパッケージの状態は
  変化しません。トレードオフ：DNF/PACMANの事前フェッチは、APT自身の「コンテナをまたいだ
  すでにキャッシュ済みのパッケージは再フェッチされない」という再利用の恩恵を失います。
  これは、共有ホスト側キャッシュディレクトリがコンテナ自身のマウント名前空間の内側から
  見えないためです — DNF/PACMANの事前フェッチはすべて、毎回新しくダウンロードし直します。
- **上記のコンテナ内修正は、最初の実地リトライでもまだ失敗しました** — dnf5は`install`に
  対する`--destdir`をそもそも拒否します
  （`Unknown argument "--destdir=..." for command "install" ... available for:
  reposync, download, upgrade`）；dnf4の`install --downloadonly --destdir=`の組み合わせは
  そのまま引き継がれません。dnf5自身のインストールせずダウンロードするコマンドは
  `download`で、デフォルトでは*指定された*パッケージのみをフェッチし、その依存関係は
  フェッチしません — `--resolve`が依存関係のクロージャ全体を引き込むもので、これが
  `install --downloadonly`が提供していたものの実際のdnf5相当品です。修正：
  `dnf download --resolve --destdir=<dir> <packages>`。上記の`groupinstall`→
  `group install`/EPEL-on-Fedoraのバグと同じ教訓です：dnf5のCLI表面はdnf4とは本物の、
  非自明な形で異なります — dnf4時代の構文がそのまま引き継がれると仮定せず、実地で確認して
  ください。
- 両方のスクリプトはさらに、URLを組み立てる前に`uname -m`のアーキテクチャ名
  （`x86_64`/`aarch64`）をimages.linuxcontainers.org自身の慣例（`amd64`/`arm64`）に
  変換します — この変換を欠くと、リリース/ビルド自体は正しくても404になります。
- 両方のスクリプトはさらに、Debianスクリプトが必要とするのと同じ
  `net.ipv4.ping_group_range`/DNSドメインのsystemd-networkdドロップインを再利用します —
  これらはsystemd-nspawn自身が生成するコンテナネットワーク設定についてのものであり、
  Debian固有の何かではないため、systemdベースのrootfsであれば*引き継がれるはず*ですが、
  それはFedora/Archに特有の点では、実地で確認された事実ではなく仮定にすぎません。

「Install package」の手動フローが持つDNF依存関係の事前フェッチ自体（`dnf install --assumeno`
経由でシミュレート、`dnf install --downloadonly`経由でフェッチ）にも、まったく同じ
「テストされるまでは未検証」という注意事項が当てはまります — 上記の
「任意のパッケージのアップロードとインストール」を参照してください。

代わりに、`debootstrap`経由で手作業でDebianテンプレートを構築することもできます
（images.linuxcontainers.orgから取得したくない場合、または別のリリース/アーキテクチャが
欲しい場合の、同じrootfs取得のアイデアです） — スクラッチディレクトリにベイクしてから、
それをgzip圧縮tarとして実際の`TEMPLATES_DIR`の場所にパッケージ化します：

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

`TEMPLATES_DIR/nspawn/`配下の各`.tar.gz`ファイルは、それぞれ1つの選択可能なテンプレート
です。`/admin/templates`（管理者専用）で対応する`Template`行を登録/編集してください —
名前、ソース識別子（`.tar.gz`もバックエンドフォルダのプレフィックスも付かない、単なる
ファイル名 — 例えば`TEMPLATES_DIR/nspawn/debian-minimal.tar.gz`に対しては
`debian-minimal`）、バックエンド、パッケージマネージャー、そして任意のインストール
コマンドの上書き設定です。すべてのテンプレートには**バックエンド**
（`domain/ContainerBackend.java`：`SYSTEMD_NSPAWN`、`PODMAN`、または`QEMU`）が対応付け
られて記録されており、それぞれ独自の`TEMPLATES_DIR`サブディレクトリとファイル形式を
持ちます — PodmanとQEMUについては下記のセクションを参照してください。新規インストール
は**ゼロ**個のテンプレートから始まります — 何もシードされていないため、このページ
（または下記の「Set up debian-minimal」ボタン）こそが、実際に最初の1つを手に入れる方法
です。上記のようにtarball自体は`TEMPLATES_DIR`配下に別途用意する必要があり、このページは
それを指すメタデータを管理するだけです。テンプレートを削除するのではなく非アクティブ化
することが、それを引退させる通常の方法です — コンテナ作成のドロップダウンからは消えます
が、それから構築された既存のコンテナには影響しません。削除は、それを参照するコンテナが
なくなった場合にのみ許可されます。このページの管理者専用ゲーティングが実際に何を保護
しているかについては、[§3](#3-sudo権限を持つsshアカウント)の「信頼境界」セクションを
参照してください。

**テンプレートは既存のマシンからも作成できます**。新規ダウンロードだけではありません：
停止中のコンテナ自身の詳細ページには「Create template from this machine」フィールド
（名前 + 任意の説明）があります。これはそのマシンの現在のrootfsを（`tar -czf`、上記の
すべてのベイクスクリプトがすでに生成しているのと同じ規約で）まったく新しい独立した
テンプレートへとパッケージ化します — ゼロから再プロビジョニングするのではなく、
所有者がすでにカスタマイズしたコンテナをスナップショットするのに便利です。マシンが
**停止中**の間だけ意図的に提供されます：稼働中のrootfsをパッケージ化すると、tar処理の
最中にファイルが変化することで不整合なアーカイブになるリスクがあるためです。管理者専用の
「New template」/「Set up X-minimal」ページとは異なり、これはコンテナ所有者のアクション
です（`/api/admin/**`配下ではなく`/api/containers/{id}/create-template`）— 結果として
できるテンプレートは、同じsudoパスワード要件を含め、それ以外はまったく同一であり、
後で他のテンプレートと同じように誰でも使うことができます。「Install package」
エンドポイントと同様、これは今のところ保存済みシークレットモードでのみ動作します
（常にnullのsudoパスワードオーバーライドを渡します） — 管理者承認モードはこのアクション
にはまだ配線されていません。

「New template」/「Edit template」フォームのソース名フィールドは、そのテンプレートが
選択したバックエンドサブディレクトリ配下にすでに存在するすべての`.tar.gz`の裸のstemを
候補として提示します（`GET /api/admin/templates/available-source-files?backend=...`から
取得され、`nspawnmgr-list-template-files.sh`— `nspawnmgr-list-machine-images.sh`と同様の
NOPASSWDな読み取り専用ラッパースクリプト — に裏打ちされています）。そのため、事前に
用意した正確なファイル名を覚えておく必要はありません。これはブラウザの`<datalist>`で
あり、厳格に制限されたドロップダウンではありません — このフィールドは自由テキストも
引き続き受け付けます。候補リストはベストエフォートであり（SSHホストに到達できない、
またはディレクトリに何もまだない場合は空になります）、tarballが実際にディスク上に
到着する前にテンプレートのメタデータを登録することを妨げるべきではないためです。

**破壊的変更：** テンプレートストレージは、`TEMPLATES_DIR/<name>`にある生の展開済み
ディレクトリツリー（`cp -a`経由で複製）から、`TEMPLATES_DIR/nspawn/<name>.tar.gz`にある
gzip圧縮tar（`machinectl import-tar`経由で複製）へと変更されました。この変更より前に
作成された`Template`行は、nspawnmgrがもはや認識しない場所を指しています — それを削除して
再作成する（例えば「Set up debian-minimal」を再クリックする）か、手動で配置したカスタム
テンプレートを上記のように新しい場所/形式にパッケージし直してください。

#### CI/CDパイプラインからのテンプレートのインストール/更新

人間が`/admin/templates`をクリックして回るのではなく、スクリプト化されたテンプレート
管理（CI/CDパイプライン自身のテンプレートをビルド・出荷する）のために、nspawnmgrは
Web APIの代わりにSSH経由で呼び出すCLIを提供します — このアプリにはマシン間のHTTP認証が
まったく存在しません（Basic認証とフォームログインはどちらも明示的に無効化されています。
唯一のログイン経路は、外部のIDサービスに裏打ちされたセッションクッキーです）。そのため
CI向けのHTTPエンドポイントを用意することは、ゼロから新しい認証機構を発明することを
意味してしまいます。CLIは代わりに、このプロジェクトの既存のSSH+sudo信頼モデルを再利用
します。

これは**2つ目の、意図的に隔離された**sudo権限を持つアカウント、`nspawnmgr_ci`を使います —
`nspawnmgr_exec`とは別のものです（理由については下記の「信頼境界」セクションを参照して
ください）。これはオプトインするまで存在しません：

```bash
sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
```

これはアカウントを作成し、パスワードログインをロックし（鍵のみの認証）、新しく生成した
SSH**秘密**鍵を標準出力に一度だけ出力します — すぐにあなたのCIシステム自身のシークレット
ストアにコピーしてください。公開鍵の半分を超えて、ホスト上には何も保存されません。後で
これを置き換えるには`--rotate-key`付きで再実行してください（古い鍵はすぐに動作しなくなり
ます。2つ目の有効な資格情報として居残ることはありません）。

CI/CDパイプラインから、tarballをSSH経由でパイプすることで、テンプレートをインストール
または更新します（upsert、`--name`をキーとします）：

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-template.sh \
  --name my-template --package-manager APT --description "Built by CI" \
  < my-template.tar.gz
```

`--name`はファイルシステムパスの一部になり（`TEMPLATES_DIR/nspawn/<name>.tar.gz`）、
それに応じて検証されます（英数字、`-`、`_`のみ）。`--package-manager`は必須です
（`APT`、`DNF`、`APK`、または`PACMAN`）；`--backend`、`--description`、
`--install-ssh-command`、`--install-xrdp-command`、`--rdp-capable`、`--active`はすべて
任意で、管理フォーム自身のフィールドとデフォルトに一致します。新規/更新されたtarballは、
データベース行が確定した後にのみ配置に組み込まれます。そのため途中での失敗が、中途半端に
インストールされたテンプレートを残すことはありません — 進行中の更新は、新しいものが
完全に準備できるまで、前のバージョンをそのまま提供し続けます。

#### CI/CDパイプラインからのパッケージのインストール/更新

同じ`nspawnmgr_ci`アカウント（上記以上の別のオプトインステップは不要）は、
[管理者パッケージキャッシュ](#任意のパッケージのアップロードとインストール)にも直接
公開できます。これは、自身の`.deb`/`.rpm`などのアーティファクトをビルドし、人間が手作業で
アップロードすることなくコンテナ所有者が利用できるようにしたいCIパイプライン向けです：

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-package.sh \
  --package-manager APT --filename my-tool_1.2.3_amd64.deb --description "Built by CI" \
  < my-tool_1.2.3_amd64.deb
```

`--package-manager`（`APT`/`DNF`/`APK`/`PACMAN`/`ISO` — ここでの`ISO`の意味については
[リムーバブルメディア](#リムーバブルメディアisoイメージ)を参照）と`--filename`は必須です
（後者は`/`を含めることも`.`で始めることもできません）；`--description`は任意です。
インストールまたは更新（upsert）は`--package-manager` + `--filename`の組み合わせを
キーとします — 同じ2つで再実行すると、前のファイルを置き換え、その行をその場で更新します。
これはテンプレートのインストールと同じクラッシュ安全性の姿勢です（ディスク上の古い
ファイルが置き換えられる前に、DBへの書き込みが確定します）。`cached_packages`は本物の
アップロードアカウント（`uploaded_by_user_id`）を必要とするため、最初のCIインストール
パッケージが専用の`nspawnmgr-ci`疑似ユーザーを自動プロビジョニングします — 管理ページと
すべてのコンテナの「Install package」セクションで、人間の管理者自身のユーザー名とまったく
同じようにアップローダーとして表示されます。

### コンテナの再起動

稼働中のコンテナの詳細ページには、Stop/Force stopと並んで**Restart**ボタンがあります。
これは`machinectl reboot`を実行します — Stop+Startとは異なる、コンテナ自身のOSをその場で
クリーンに再起動する処理です：マシンの登録とそのvethインターフェースが破棄・再作成される
ことは決してないため、カスタムポートマッピング、アウトバウンドアクセスのファイアウォール
状態、およびそのvethに紐づく他のすべてが、再同期を必要とせずに有効なまま残ります。
`ContainerReadinessPollingService`がSSH（RDPが有効ならそれも）が戻ってくるのを待つ間、
コンテナは新規起動時と同じBOOTING状態を経由します。

### コンテナの一時停止と再開

稼働中のコンテナの詳細ページには、Stop/Force stopと並んで**Pause**/**Resume**ボタンが
あります。Stopとは異なり、何も破棄されません：Pauseはコンテナ自身の
`systemd-nspawn@<name>.service`ユニットに対して`systemctl freeze`を実行し、カーネルの
cgroupフリーザー（systemd 246以降）を介してそのcgroup内のすべてのプロセスをその場で
一時停止します；Resumeは`systemctl thaw`を実行してこれを元に戻し、中断した箇所から正確に
再開します。`machinectl`自体にはネイティブな一時停止/再開の概念はありません — これは
現代的なsystemdネイティブの等価物であり、`systemctl freeze`/`thaw`が他のあらゆるユニット
種別に対してすでに提供しているのと同じ仕組みです。

`machinectl start`（nspawnmgrが常にコンテナを起動する方法）経由で起動されたコンテナは、
別の`machine-<name>.scope`を持たず、直接`systemd-nspawn@<name>.service`ユニットとして
動きます — Pause/Resumeが対象とするのはそのサービスユニットです。freeze/thawはcgroupを
持つ任意のユニットに対して機能し、サービスユニットも含まれます。freeze/thawの*挙動*自体
（フリーザーコントローラーが利用可能/有効になっているか、プロセスが本当に正しく
一時停止/再開するか）は、これに大きく依存する場合は実地で確認する価値がまだあります。

### ホスト起動時の自動起動

MANAGEDコンテナの詳細ページ（自身の`machinectl`イメージを持たないEXTERNALホストでは
表示されません）には、2つのフィールドを持つ**Machine settings**パネルがあります：

- **ホスト起動時に自動的に開始する** — コンテナ自身の`systemd-nspawn@<name>.service`
  ユニットに対する`systemctl is-enabled`/`enable`/`disable`に裏打ちされたチェックボックス。
- **このマシンがすでに起動していることを要求する** — 他のすべてのMANAGEDコンテナの名前の
  ドロップダウンで、
  `/etc/systemd/system/systemd-nspawn@<name>.service.d/nspawnmgr-requires.conf`にある
  systemdユニットのドロップインに裏打ちされます（選択したマシン自身のユニットに対する
  `Requires=`/`After=`。変更のたびに`systemctl daemon-reload`されます）。上記の自動起動
  と組み合わせた場合にのみ意味があります — これは、両方とも自発的に起動する2つのマシン間
  の起動*順序*を制御するものであり、Stop/Startが別途強制するランタイムの依存関係では
  ありません。

どちらのフィールドも**ページを読み込むたびにホストからライブで読み取られ、nspawnmgr自身の
データベースには保存されません** — これは意図的なものです。管理者がnspawnmgrの外側で
ホスト上で直接`systemctl enable`/`disable`を実行することを止めるものは何もなく、
キャッシュされた値は`systemd`が実際に設定している内容から静かにずれてしまう可能性が
あるためです。これらを読み取る際の一時的なSSH不調は、ページ全体を失敗させるのではなく、
ページ上にフォールバックメッセージを表示します；変更の保存は、読み取りと同じ2つの
ラッパースクリプト（`nspawnmgr-set-machine-autostart.sh`/`nspawnmgr-set-machine-requires.sh`、
どちらもNOPASSWD — 日常的な、所有者が起動する、Start/Stopと同じ階層）を経由します。

**自己ホスト型`nspawnmgr`マシンとそのデータベースマシン**（[§1](#1-アーキテクチャ概要)参照）
はどちらもこの方法で自動的に自動起動するよう設定されており、`nspawnmgr`は自身の
データベースマシンを要求するように設定されています — そうしないと、ホストの再起動が
自身のデータベースに到達可能になる前に`nspawnmgr`を起動してしまう可能性があります。
これは`ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()`
（両方のマシンを`debian-minimal`テンプレートに紐づけ、それらの管理対象SSHアクセスを
プロビジョニングし、それらのコンテナ一覧の説明を設定する、同じ自己ホストインフラ
整合パスです — [§1](#1-アーキテクチャ概要)と
[「nspawnmgrの外部で作成されたマシンの検出」](#nspawnmgrの外部で作成されたマシンの検出)
を参照してください）によって配線されており、これはnspawnmgr自身のSpringアプリが起動した
瞬間から、独自の繰り返しの約30秒スケジュールで実行されます — 管理者のアクションによって
ゲートされていません。一時的な失敗（WARNレベルでログされ、致命的ではありません）は、
管理者のアクションを必要とせず、単に次回のパスで再度拾われます；同じ整合処理は、手動での
**Discover machines**クリックの一部としても引き続き実行されます。

### コンテナのネットワーキング

管理対象のすべてのコンテナは、それぞれが独立したポイントツーポイントの専用サブネット上の
vethを持つのではなく、1つのブリッジ`nspawnbr0`（生成される`.nspawn`ファイル内の
`Bridge=nspawnbr0` — `machinectl start`が起動時に各コンテナ自身のvethを自動的にそこに
従属させます）を共有します。`nspawnbr0`とそのアドレス（`10.100.0.1/24`、固定で管理者が
設定可能ではありません — 内部の規約であり、本物のカスタマイズポイントではありません）は
`.deb`自身のpostinst（`/etc/systemd/network/70-nspawnmgr-bridge.netdev`/`.network`）に
よって無条件に作成されます。手作業でセットアップするものではありません。**Network
diagnostics**には、これが実際に稼働していることを確認する読み取り専用のチェックが
あります。

**SSH/RDP/VNCはインバウンドの転送をまったく必要としません。** Guacamoleの`guacd`と
nspawnmgr自身のレディネスポーリングは、どちらもMANAGEDコンテナの内部veth
アドレス（その`host0`インターフェース、`machinectl`/`nsenter`経由でライブに解決されます —
`nspawnmgr-get-internal-address.sh`参照）に、コンテナの実際のsshd/xrdp/VNCポート
（22/3389/5900）で直接ダイヤルします。これらについては、ホストポート転送がループの中に
まったく存在しません。これは、実ハードウェアで確認された同一ホスト内ヘアピンNATの制限を
回避します：ホスト自身から、自身のDNAT済み/転送済みアドレス経由でコンテナへ戻る
トラフィックは、正真正銘外部のクライアントが同じアドレス+ポートに到達する場合には正常に
動作するにもかかわらず、しばしば正しく再NATされません。コンテナに割り当てられた内部
アドレスは、RUNNINGに到達した瞬間に（INFOレベルで）ログされ、アドレスが変わった場合に
備えて、以降の再起動のたびにGuacamoleの接続設定に再同期されます。

### グラフィカルアクセス：RDP、VNC、デスクトップマネージャー

「New Nspawn」フォームには2つの独立したチェックボックス、**Enable RDP**と**Enable VNC**が
あります — どちらか、両方、またはどちらもなし。どちらかを選ぶと**Desktop manager**
ドロップダウン（None/GNOME/KDE（`kde-standard`）/Xfce（`xfce4`））が現れます：
グラフィカルなプロトコルは、最小限のテンプレート内に実際のデスクトップ環境がなければ
使い道が限られるため、1つを選ぶとプロビジョニング中にそれがインストールされ、RDPとVNC
の両方が選ばれた場合は共有されます。**None**は追加で何もインストールされないことを
意味します。

下記でカバーするプロンプト資格情報によるアクセスとは異なり、作成時に選択されたRDP/VNC
は、nspawnmgrが作成・保存する本物の生成済みアカウント/パスワードを得ます（RDPは
`chpasswd`経由でログインパスワードが設定されたSSHアカウントを再利用します；VNCは同じ
アカウントを再利用しますが、`vncpasswd`経由でVNC専用のパスワードのみを設定します — これ
自身のLinuxログインパスワードは必要ありません）。正確な`vncserver`/`xstartup`/
パッケージインストールのシーケンスは、現用の実際の`debian-minimal`（APT）テンプレート
1つに対してのみ実行検証されています — これを含む`.deb`をインストールした後、改めて
確認する価値があります。

### Podman：ポッド

nspawnコンテナに加えて、「+」メニューの**New Pod**は本物の`podman`実行コンテナを
作成します（Machinesグリッド上のバッジは`PODMAN`、`NSPAWN`/`QEMU`/`HOST`と並びます）—
所有権/共有ルール、カードグリッド、詳細ページの関係性はここにある他のすべてと同じ
です。これはログイン済みの任意のユーザーが利用可能で、管理者限定ではありません；
podmanバックエンドのテンプレートがまだ存在しない間だけリンクが無効化されます。これは
New Nspawnと同じ姿勢です。

**作成**（`/containers/new-pod`）：名前、テンプレート（podmanバックエンドのテンプレート
のみのドロップダウン）、説明、そして任意のコマンド — Dockerfileの`CMD`オーバーライドの
ようなものです；空欄のままにすると、イメージ自身に焼き込まれたコマンドを信頼します。
コマンドとして裸の対話型シェルを指定すると、その標準入力に何も接続されなくなった瞬間に
終了し、失敗としてではなくポッドがSTOPPEDになります — 最初のポッドが作成直後に消えて
しまったように見える場合に知っておく価値があります。プロビジョニング
（`ProvisioningService.provisionPod()`）はテンプレートのイメージをロードし、コンテナを
作成・起動し、所有者にアクセス権を付与し、その内部アドレスを解決・永続化し、まっすぐ
**RUNNING**に到達させます — nspawnコンテナとは異なり、`BOOTING`/レディネスポーリングの
フェーズはありません。`podman create`+`start`は同期的であり、ポッドはそもそもポーリング
すべき自動プロビジョニングされたSSH資格情報を得ないためです。

**ネットワーキング**：ポッドはnspawnコンテナと同じ`nspawnbr0`ブリッジを共有しますが、
DHCPではなくnetavarkの**host-local IPAM**を使う専用のpodmanネットワーク定義
（`/etc/containers/networks/nspawnbr0.json`、`nspawnmgr-configure-podman-network.sh`
によって書き込まれます）を介します — netavark自身のDHCPプロキシはホストのネットワーク
名前空間から送信し、カーネルはそのトラフィックをブリッジ自身の受信キューに決して
ループバックしません。これは未探索の選択肢ではなく、確認済みの行き止まりです。
アドレスプールは衝突を避けるためnspawn自身のDHCP範囲から分割されています：ポッドは
`10.100.0.192`–`10.100.0.254`を、nspawnコンテナは`10.100.0.2`–`10.100.0.191`を保持します。
DNSはポッドが決して受け取ることのないDHCP配布の設定に頼るのではなく、作成時に明示的に
設定されます（`podman create --dns 10.100.0.1 --dns-search internal ...`）— podman自身の
`aardvark-dns`は、nspawnmgr自身のdnsmasq（すでに同じアドレスにバインドされています —
上記の[「名前によるコンテナの解決」](#名前によるコンテナの解決)参照）との衝突を避ける
ため、このネットワークに限って無効化されています。

**ライフサイクル**はnspawnコンテナと完全に同等です — Start/Stop/Restart/Pause/Resumeは
すべて、nspawn固有の仕組みではなく、ネイティブなpodmanコマンド
（`start`/`stop`/`kill`/`restart`/`pause`/`unpause`）にディスパッチされます。別個の
**`ContainerLivenessPollingService`**が、すべての`RUNNING`ポッドの実際のpodman状態
（および下記のすべての`RUNNING`なQEMU VMの実際のユニット状態）を独自の約30秒スケジュール
で再チェックし、現実が食い違った瞬間にnspawnmgr自身の状態を`STOPPED`に反転させます —
ポッドが（不正な、または欠落したキープアライブコマンドにより、上記のCommandフィールド
参照）自発的に完全に終了しても、アプリの他の部分がそれにまったく気づかない可能性が
あるため、これが必要です。ポッドはnspawn専用のレディネスポーリング経路を完全にスキップ
するためです。`PAUSED`のポッドはポーリングされません。

**アクセス**：SSH/RDP/VNCは**プロンプト資格情報のみ**です。これはHostsと検出された
コンテナが使うのと同じ、到達可能性ゲート付きの仕組みです
（[上記の§参照](#nspawnmgr自身がセットアップしていないコンテナへのリモートアクセス)）—
ゲスト自身のサービスが実際にリッスンし始めた時点で、ポッド自身の詳細ページから
プロトコルごとに有効化します。ポッドは、nspawnコンテナのSSHアクセスのような自動生成の
資格情報を決して得ません。

**Files**は`podman mount`経由で動作します。これはコンテナのマージされたオーバーレイ
ファイルシステムを通常のホストパスとして公開します — 同じ参照/アップロード/ダウンロード
のコードが、その後そのパスに対して直接実行されます。

**Scripts**は`podman exec -i <name> sh -s`（パイプされた標準入力、nspawnmgrへの本物の
終了コード）経由で実行されます。Abortは、nspawnの一時的なユニットkillよりも狭い近似
です：スクリプト本体には`echo $$ > <pidfile>`が前置され、Abortはその記録されたプロセス
グループに`kill -9`を送ります — 本物のプロセスグループkillですが、nspawnのabortのような
真のcgroup全体のものではありません。これはバグではなく、既知の意図的な縮小として
コード内に文書化されています。

**ポッドには明示的に提供されないもの**（nspawnコンテナにはすべて存在します）：自動
プロビジョニングされたSSH/RDP/VNC資格情報なし、デスクトップマネージャーのインストール
なし、カスタムインバウンドポートマッピングなし、アウトバウンドファイアウォールの
トグルなし（ポッドはすでにnetavark経由の本物のネットワークアクセスを持っています —
ゲートするものが何もありません）、ISOマウントなし、`machinectl`スタイルの
自動起動/要求設定なし。

**テンプレート**は`TEMPLATES_DIR/podman/<name>.tar`配下に置かれます — `podman save`
アーカイブで、作成時に`podman load`経由でロードされ、nspawnの単純なtar規約とは異なり
ます。レジストリから直接プルする（`nspawnmgr-podman-pull-template.sh`）か、既存の
nspawnテンプレートを変換する（`nspawnmgr-podman-convert-nspawn-to-podman.sh`、
逆方向は`nspawnmgr-podman-convert-podman-to-nspawn.sh`）ことで、いずれかを準備して
ください。停止中のnspawnまたはQEMUマシン自身の詳細ページが提供する
「このポッドからテンプレートを作成」の便宜は、現在は存在しません — 新規プルまたは変換
のみです。

podmanバックエンド用の専用の自動テストスイートは存在しません（`*Podman*`テストクラス
なし）— フェイクに対して実行される一般的なテストスイートと、yoga上での手動の
開発スタックおよび実地でのクリックスルーによってカバーされています。DNSの修正と
上記のnetavark host-local-IPAMネットワーキングの決定は、どちらも実地で確認済みです
（`nspawnmgr-configure-podman-network.sh`と`nspawnmgr-podman-create-container.sh`
自身のヘッダーコメントを参照）— プロセスグループkillによるabortの近似が、既知の、
意図的なギャップの主なものです。

### QEMU：仮想マシン

nspawnコンテナとpodmanポッドに加えて、「+」メニューの**New QEMU**は本物のQEMU/KVM
仮想マシンを作成します（バッジは`QEMU`）。同じ所有権/共有ルールで、同じMachinesグリッド
上に表示されます。ログイン済みの任意のユーザーが利用可能です；ホストにQEMUがインストール
されていない間はリンクが無効化されます（Diagnosticsページ参照）。

**作成**（`/containers/new-qemu`）：名前；ディスクソース — **空のディスク**（GB単位の
サイズ）または**テンプレートから**（既存のQEMUバックエンドのTemplate自身のディスクを
複製）、どちらか一方のみ；**プロセッサタイプ**；**CPU数**；**メモリ（MB）**；
**ネットワークカード**（NICデバイスモデル — デフォルトは`virtio-net-pci`、または特定の
ものが必要なゲストOS向けに`e1000`/`rtl8139`/`pcnet`、例えばFreeDOSは通常`pcnet`が必要
です）；**ポインタデバイス**（デフォルトは`PS/2`、または`USB tablet`。これはGUIゲストの
VNC下でのマウスカーソルのドリフトを修正しますが、DOS系のゲストにはUSBドライバスタック
がまったくなくPS/2が必要です。これがUSB tabletではなくデフォルトのままである理由です）；
そして任意の**Boot ISO**。

`POST /api/containers/qemu`は、ディスクサイズ/テンプレートフィールドのどちらか一方だけ
が設定されていることを検証し、その後`ProvisioningService.createPendingQemu()`が行を
永続化し、`provisionQemu()`が実際の作業を行います：テンプレートのディスクを複製する
か新しく空のものを作成し、VNCポートを割り当て、VMのsystemdユニットを書き込み、それを
起動し、VNCパスワードを生成・保存し、対応するGuacamole VNC接続を作成します — 上記の
ポッドと同じ同期起動の理由で、すぐに**RUNNING**に到達します（`BOOTING`/レディネス
ポーリングなし）。別個の非同期`QemuAddressPollingService`が、純粋にSSH目的で、その後
ゲストIPの解決を試みます — 「まだ準備できていない、場合によっては長時間」というのは、
まだディスクにゲストOSがインストールされていない可能性すらある、新しく作成された
VMにとって期待される、正常な状態です。

**ディスク作成**（`nspawnmgr-qemu-create-disk.sh`）は、`/var/lib/nspawnmgr/qemu-disks/`
配下での単純な`qemu-img create -f qcow2 <path> <size>G`です。他の新しい永続的な
アーティファクトと同じPASSWORD階層のsudoです（[§3](#3-sudo権限を持つsshアカウント)参照）
— その後実際にVMを起動するのは別のNOPASSWDステップです。

**VMのsystemdユニット**（`nspawnmgr-qemu-write-unit.sh`）は、
`/etc/systemd/system/nspawnmgr-qemu-<name>.service`にある本物の永続的なユニットです —
作成時と、VMが停止している間にマウントされたISOが変わるたびの両方で、一度だけでなく
書き直されます（下記参照）。これに対する単純な`systemctl start/stop`（これがnspawnmgrが
常にQEMU VMのライフサイクルを駆動する方法です）は単なる裸のマシン名を取るだけで、
呼び出しを再構築するためのVM固有の情報が何もないため、これは一時的な`systemd-run`
呼び出しではなく永続的なものになっています。その`ExecStart`行がカバーするのは：メモリ/
CPUモデル/CPU数/`-enable-kvm`フラグ（KVMは`/dev/kvm`の存在によって自動検出されます）；
virtioドライブとしてのqcow2ディスク；`nspawnbr0`上のネットワークカードで、VMの名前から
決定論的に導出されたMACアドレス（`52:54:00:` + 名前のmd5ハッシュの最初の3バイト —
アドレス解決スクリプトは、どちらのスクリプトもそれを永続化しないため、この同じ値を
独立して導出しなければなりません）；ポインタデバイスのフラグ（PS/2の場合は空、
USB tabletの場合は`-usb -device usb-tablet`）；VNCリスナー；Unixソケットの
QEMUモニター；そしてブート順序（ISOがマウントされている場合は`-cdrom ... -boot
order=d`、そうでなければ`-boot order=c`）です。`qemu-system-x86_64`が`PATH`上にない
場合は`/usr/libexec/qemu-kvm`にフォールバックします（Fedora/RHELのパッケージングの
癖で、`nspawnmgr-diag-check-qemu.sh`がすでに使っているのと同じフォールバックです）。

**VNCアクセス**：ポートは管理者が設定可能な範囲から割り当てられます
（[`/admin/settings`](#ライブ編集可能な設定adminsettings)、`5900`以上から開始するよう
検証されます — QEMU自身の`-vnc host:display`構文はディスプレイ番号でアドレス指定し、
`display = port - 5900`です）、他のVMにまだ確保されていない最も低い空きポートを選び
ます。リスナーは常に`nspawnbr0`自身のゲートウェイアドレス（`10.100.0.1`）にバインド
します — Guacamoleがコンテナ自身の内部アドレスに直接ダイヤルするnspawn/podmanとは
異なり、すべてのQEMU VMのハイパーバイザーコンソールは1つのアドレスを共有し、ポートに
よってのみ区別されます。生成されたパスワードを持つGuacamole VNC接続は、プロビジョニング
時に自動的に作成されます — 所有者が有効化する必要はなく、すでにそこにあります。QEMU
自体は再起動をまたいでそのパスワードを永続化しないため、`ContainerLifecycleService`が
起動/再起動のたびにHMPモニター（下記参照）経由で保存済みの資格情報を再適用します。

**HMPモニター**は内部専用です — 任意のモニターコマンドを送信するUIはありません。
`nspawnmgr-qemu-monitor-exec.sh`は、SSH経由で一度に1つのHMP行をVMのモニターUnix
ソケットへ`socat`経由でリレーします（QEMUが応答しなくなってから2秒後に接続を閉じます。
HMPのプレーンテキストREPLには、完了を検出するためのクリーンなレスポンスごとの
フレーミングがないためです — 実際の`qemu-system-x86_64`モニターに対してはまだ検証
されていないと文書化された、出発点です）。これは以下を支えます：グレースフルな
Stop（`system_powerdown`、ACPIリクエスト — まだゲストOSがインストールされていない
場合は設計上のno-opであり、バグではありません）；Pause/Resume（`stop`/`cont` — nspawn
コンテナが使うcgroupフリーザーではなく、QEMU自身の等価物です）；上記のVNCパスワードの
再適用；そしてライブのISO交換（`change ide1-cd0`/`eject ide1-cd0`）。

**QEMU VMではFilesアクセスは利用できません** — podmanの`podman mount`とは異なり、
ストレージが単一のqcow2ディスクファイルであるVM向けに参照できるホスト側ディレクトリが
なく、実際のゲスト側アクセス（有効化されればVM自身のSSH接続経由のSFTP）はまだ構築
されていません。この理由により、QEMU VMのカード上ではFILESピルが無効化されています；
将来のリリースで計画されています。

**ISOマウント**は、nspawnコンテナと同じ`PackageManager.ISO`パッケージキャッシュを
再利用します（[上記の§参照](#リムーバブルメディアisoイメージ)）。VMの次回起動時にのみ
効果を持つnspawnの静的バインドマウントとは異なり、QEMUはVMが現在稼働している間に
HMPモニター経由で**ライブスワップ**でき、さらに同じ選択を（上記で言及した同じ
`nspawnmgr-qemu-write-unit.sh`の書き直しを介して）ユニットファイルにも別途永続化する
ため、次にVMがコールドスタートしたときにも正しい状態になります。

**テンプレート**：既存のQEMUバックエンドのTemplate（`TEMPLATES_DIR/qemu/<name>.qcow2`）
からVMのディスクを複製することは、上記で説明した空のディスク+ISOの経路と並んで完全に
サポートされています — New QEMUフォームで**From template**を選んでください。停止中の
VM自身の詳細ページにも、nspawnコンテナが使うのと同じ規約の「Create template from this
machine」フィールドがあり、VMの現在のディスクをまったく新しい独立したテンプレートへ
スナップショットします。

**ライフサイクル**は、上記の永続的なsystemdユニットを通じてnspawn/podmanと完全に同等
で、加えてQEMU自身がグレースフルに実行するよう依頼されなければならない操作のための
HMPモニターがあります：Start、Force stop、RestartはVM自身のユニットに対する単純な
`systemctl start/stop/restart`です；グレースフルなStopとPause/Resumeは、
`systemctl freeze`/`thaw`ではなく、上記のようにHMP経由で行われます。

**クラッシュ整合**：上記でpodmanについて説明したのと同じ`ContainerLivenessPollingService`
がQEMUもカバーします — すべての`RUNNING`なVM自身のユニットは同じ約30秒スケジュールで
再チェックされ（`systemctl is-active`）、ユニット自体が停止または消失した瞬間に
nspawnmgr自身の状態が`STOPPED`に反転します。**これはまだ本物の限界であり、完全には
解決されていません**：これはユニット/プロセス自体が消えることしか検出せず、プロセスは
生きたままだが内部で実行中の何かがハング/終了したというゲストOSのみのクラッシュは
検出しません — `systemctl is-active`にはそれを見通す手段がなく、どちらのバックエンドも
問い合わせる方法を提供していません。プロセスが技術的には稼働し続けているにもかかわらず
VMのバッジが現実と食い違っているように見える場合、覚えておく価値があります。

QEMUバックエンド向けの専用の自動テストスイートも同様に存在しません（`*Qemu*`テストクラス
なし）— フェイクに対する一般的なスイートと、手動の開発スタックおよび実地での
クリックスルーによってカバーされています；ポインタデバイスの設定は特に、yoga上の
実際のKolibriOS VMに対して実地で確認済みです。上記のHMPモニターのレスポンスフレーミング
のヒューリスティックと、`nspawnmgr-diag-check-qemu.sh`のいくつかのチェックは、それぞれ
自身のヘッダーコメントの中で、実際の`qemu-system-x86_64`モニターに対しては明示的に
未検証と記されています。

**Discover machines**（[上記の§参照](#nspawnmgrの外部で作成されたマシンの検出)）は
ワンクリックで3つのバックエンドすべてをカバーします — `machinectl`、`podman`、そして
QEMU自身のsystemdユニットそれぞれに対して別個のパスを実行し、それらのいずれかで
見つかった未追跡のものを登録し、ホストにまったくインストールされていないバックエンドは
そのままスキップします。

### パッケージのインストール：ライブのネットワーク取得から直接インストールせず、先にダウンロードする

*コンテナの内側から*実行されるパッケージマネージャーは、ホスト自身のネットワーク/DNSが
問題なく動作している場合でも、自身のミラーの解決が信頼できないことが確認されています。
SSH、RDP、VNC、そしてデスクトップマネージャーのパッケージはすべて同じ扱いを受けます：
nspawnmgrはコンテナの*内側*で実際のインストールを実行する前に、それらを（完全な依存
関係のクロージャとともに、ダウンロードのみで — まだ何もインストールされません）
ダウンロードします。デフォルト（上書きされていない）インストールコマンドを使う
**APT、DNF、PACMAN**のテンプレートに適用されます — カスタムのインストールコマンドの
上書きは、事前フェッチすべきパッケージ名を安全に解析できないため、今日のコンテナ内
のみのインストール（コンテナ自身のネットワーク/DNSが実際に動作している必要があります）
にフォールバックします。**APK**は完全に除外されます：自身のローカルインストールが
すでに設定済みのリポジトリから依存関係を独自に解決するため、事前フェッチは不要です
（どのみち意味がありません — Alpineベースのコンテナは今日このアプリでは完全には
動作しません。下記参照）。

**APT自身のダウンロードステップはホスト側で実行されます** — コンテナ自身のrootfs
ディレクトリを直接指すプロセス（`apt-get -o Dir=<rootfs>`）で、ホスト自身の動作中の
ネットワークを使います。`apt-get`は常にこのホスト自身の`PATH`上にあるためです（この
プロジェクトの`.deb`はDebian/Ubuntuのみを対象としています）。**DNFとPACMANはそうは
できません**：どちらもこのホスト自身の`PATH`上にまったく存在しないため、それらの
ダウンロードステップは代わりに*コンテナ自体の内側*で、`systemd-run --machine=`経由で
実行されます（実際のインストールステップがすでに使っているのと同じ非対話式のin-container
実行プリミティブです）— APTと同様にダウンロードのみで、dpkg/rpm/pacmanのインストール済み
パッケージ状態には触れません。1つの帰結：DNF/PACMANは、APT自身の「すでにキャッシュされ
まだ有効なパッケージは再フェッチされない」というコンテナをまたいだ再利用の恩恵を得ません
（これは、コンテナ自身のマウント名前空間の内側で動くdnf/pacmanからは見えない、単純な
ホスト側キャッシュディレクトリに依存しています）— DNF/PACMANの事前フェッチはすべて、
毎回新しくダウンロードし直します。3つすべてが、管理者Packagesページの可視性のために、
ダウンロード自体がどこで実行されたかにかかわらず、
`/var/cache/nspawnmgr/packages/<manager>/auto/`配下にそのクロージャをキャッシュします。

1つの例外：GNOME/KDEはDNFではcompsの*グループ*（`dnf group install`）経由でインストール
されます。単なる名前付きパッケージではありません — `dnf --downloadonly`（事前フェッチが
使うもの）には、グループ全体のメンバーシップを事前に解決/キャッシュする等価な機能が
なく、個々のパッケージのみが対象です。そのためこれら2つの組み合わせは意図的に
事前フェッチをスキップし、そのままコンテナ内のグループインストールに進みます
（コンテナ自身のネットワーク/DNSが必要になり、上書きされたコマンドの場合と同様です）。
Xfceにはこの問題はありません — 実際に確認済みで、Fedoraはこれをcompsグループとして
ではなく、単なる名前付きパッケージ（`xfce4`）として出荷しています。

その実際のコンテナ内インストールステップ自体は、`apt-get update`/dnf自身のメタデータ
更新を再実行することは決してありません：これは冗長です。事前ダウンロードステップが
すでに数瞬前にそのインデックスを更新している（APTについてはホスト側で、DNF/PACMANに
ついてはコンテナ内で）ため、インストールステップが読み取るものはすでに新しく、必要な
すべてのパッケージはすでにコンテナ自身のローカルキャッシュに置かれています — 各事前
フェッチスクリプトは、まさにこの理由のためにそこにコピーを残します。

トップレベルのパッケージ自体（その推移的な依存関係は、キャッシュディレクトリの実装
詳細のままです）は、下記の**Packages**管理者キャッシュにも登録されます。そのため
nspawnmgrが自身のプロビジョニングのためにフェッチしたものも、1つのコンテナ作成の隠れた
副作用としてだけではなく、そこでも可視で再利用可能です。

### 任意のパッケージのアップロードとインストール

管理者は任意のパッケージファイルを直接アップロードすることもできます：**Packages**
（コンテナ一覧から、管理者専用）は`.deb`/`.rpm`/使用中のパッケージマネージャーが使う
何であれのファイルと、任意の説明を受け付けます。各コンテナ所有者は、自分のコンテナ
自身の詳細ページに対応する**Install package**セクションを見ます（そのコンテナ自身の
パッケージマネージャー向けのパッケージのみが提示されます）— 1つを選んでInstallを
クリックすると、それがコンテナにコピーされ、その後**APT、DNF、PACMAN**パッケージに
ついては、まずコンテナ自身の状態に対してインストールを*シミュレート*し
（`apt-get install -s` / `dnf install --assumeno` / `pacman -U --print`、変更は
行われません）、まだ持っていない依存関係を見つけます。不足しているものはすべて、
SSH/RDP/VNC/デスクトップマネージャーのプロビジョニングがすでに行っているのと同じ方法で
（上記参照 — APTはホスト側で、DNF/PACMANはどちらもこのホスト自身の`PATH`上に存在しない
ため`systemd-run --machine=`経由でコンテナ自体の内側で）フェッチされ、パッケージ
キャッシュにもここに登録されます。その後、実際のインストールがパッケージマネージャー
自身のローカルファイルインストールコマンド（`apt-get install <path>` /
`dnf install <path>` / `pacman -U --noconfirm <path>`）経由で実行されます — その自身の
依存関係解決が、アップロードされたファイルと、たった今事前フェッチされたものの両方を
1つの首尾一貫したパスで拾い上げます。DNF/PACMAN自身のローカルインストールは、通常であれば
任意の名前付きパッケージに対してと同じように、コンテナ自身のネットワークアクセスから
直接依存関係を解決します — 事前フェッチのステップは、実際のインストールコマンドではなく
ダウンロードのみの非対話式のシングルステップにその必要性を封じ込めながら、「コンテナに
ライブのパッケージマネージャーミラー検索のために直接ネットワークへ到達させない」という
APT自身の姿勢との一貫性のために、それでも意図的に実行されます（DNF/PACMAN自身の事前
フェッチも、コンテナ内ダウンロード自体のためにコンテナのネットワークをそれでも必要と
します）。このサブステップにはコンテナ作成と同じsudoパスワード階層が必要なため、保存済み
のsudoシークレットが設定されておらず、かつリクエストが1つも提供しなかった場合は
（中途半端なインストールを黙って行うのではなく）完全に失敗します。**Fedora/Archコンテナ*
*にアップロードされたパッケージをインストールするためのDNFとPACMANのサポートは未検証
です** — RPM/Archホストに*nspawnmgr自体*をインストールする（検証済み。上記のRPMとArch
パッケージインストールのセクション参照）のとは別物であり、この特定のコンテナ内パッケージ
アップロードフローは実際のFedora/Archコンテナに対して一度も実行されたことがなく、
できる限り注意深く各ツールの文書化されたCLI契約に沿って構築されているだけです —
実地での不一致を見つけたら報告してください。**PACMANの方が2つのうちより推測に基づいて
います**：`apt-get install -s`/`dnf install --assumeno`はapt/dnf自身のよく文書化された
ドライラン用モードですが、これとは異なり、`pacman -U --print`のローカルファイル依存関係
クロージャの完全なシミュレーションについての振る舞いは、このプロジェクトのどこでも
（手動でさえも）一度も実行されたことがありません。**APK**パッケージはこれらすべてを
スキップし、依存関係解決なしで単一のローカルインストール（`apk add <path>`）だけを
実行します — そこで不足している依存関係は依然として出力中の目に見えるエラーであり、
自動的には修正されません（APK自身のローカルインストールは実際には設定済みのリポジトリ
から依存関係を解決しますが、Alpineベースのコンテナはどのみち今日このアプリでは完全には
動作しません — 下記参照）。nspawnmgrが自動ダウンロードしたパッケージ（自身の
SSH/RDP/VNC/デスクトップマネージャーのプロビジョニングのため、またはこのフローによって
フェッチされた依存関係として）も、管理者が手作業でアップロードしたものと並んで、
どのコンテナの作成またはインストールが最初にそれらをフェッチしたかに紐づけられて、
ここに表示されます。

Packagesページの**「Show transitive dependencies」**ボタンは、これが意図的に残している
ギャップを埋めます：パッケージマネージャー（APT/DNF/PACMAN、事前フェッチキャッシュ
ディレクトリを持つ同じ3つ）を選ぶと、そのマネージャーの共有
`/var/cache/nspawnmgr/packages/<manager>/auto`ディレクトリに実際に置かれているすべての
ファイルを、バイト単位のサイズとともにリストします。これはボタンがクリックされるたびに
実際のディレクトリをシェルアウトして読み取ることで新しく生成されます
（`nspawnmgr-list-auto-cache.sh`、NOPASSWDの読み取り専用ラッパースクリプト）— これに
ついては、上記の表のトップレベルパッケージとは異なり、データベースには何も保存されて
いません。依存関係が実際にディスク上に届いたことを確認したり、あるパッケージマネージャー
がそれまでにこの共有キャッシュディレクトリをどれだけ蓄積してきたかを一目で確認したり
するのに便利です。

### リムーバブルメディア（ISOイメージ）

**ISO**は別個のキャッシュ/エンティティ/管理ページではなく、本物の`PackageManager`
の値です — 同じ**Packages**管理ページから、`.deb`/`.rpm`とまったく同じようにアップロード
し、`APT`/`DNF`/`APK`/`PACMAN`の代わりに`ISO`を選びます。`.deb`/`.rpm`スタイルの
インストールの仕組みはこれには適用されません（`ISO`にはインストールコマンドがなく、
`Template.packageManager`が`ISO`になることは決してありません — Templates管理フォーム
自身のドロップダウンからは除外されています）が、アップロード/キャッシュ/CI公開の経路は
どちらの場合でも同一です。これは、2つ目の並行した経路を構築するのではなく、意図的な
選択です。その後、任意のコンテナ所有者は、自分自身のコンテナの詳細ページの
「Removable media」セクションから、アップロード済みのISOを設定できます — 本物のCD
ドライブのように、一度につきコンテナごとに最大1つ、常に固定の`/mnt/cdrom`に読み取り
専用でマウントされます。すでに1つ設定されている状態で別のISOをマウントすると、最初に
古いものが自動的にイジェクトされます；別個のイジェクトしてからマウントというステップは
ありません。

**[カスタムポートマッピング](#カスタムポートマッピングとアウトバウンドアクセス)とまさに
同じ、永続的で宣言的な設定であり、ライブの操作ではありません。** マウント/イジェクトは
コンテナの`.nspawn`ファイルを即座に書き換えます（静的な`[Files]`の`BindReadOnly=`行）が、
これはコンテナが次回（再）起動されたときにのみ有効になり、明示的に変更またはイジェクト
されるまで再起動をまたいで設定されたままになります — これを設定するのにコンテナが稼働
している必要は*なく*、停止/再起動によってこれがクリアされることも*ありません*。ホスト
側の半分（固定のコンテナごとのパスにループマウントされたISOファイル、
`nspawnmgr-mount-iso.sh`/`nspawnmgr-unmount-iso.sh`）は、その時点でコンテナがたまたま
稼働しているかどうかにかかわらず、マウント/イジェクトした瞬間にセットアップ/解体
されます；ただし、ホストの再起動はそのループマウントを自発的に再確立しないため、ISOが
まだ設定された状態でホスト再起動後に起動されたコンテナは、これが手作業で対処される
（`mount -o loop,ro <iso> /var/lib/nspawnmgr/iso-mounts/<name>`）まで起動に失敗します —
これは既知の制限であり、今日は自動的には整合されません。

**これにより`systemd-networkd`は、単にアウトバウンドアクセスのための好ましい存在という
だけでなく、絶対的な前提条件になります** — nspawnmgr自身のpostinstは、これを使って
`nspawnbr0`自体を作成・設定します（上記参照）、そしてnspawnmgrのレディネスチェックと
`guacd`はどちらも、コンテナが`host0`アドレスを持った瞬間にそれへ直接ダイヤルします。
そのため、それを決して得られないコンテナ（テンプレート内で`host0`が有効化されていない
— 下記のステップ2参照）は、ゆっくりとではなく、まったく`BOOTING`から抜け出しません。
コンテナがRUNNINGに到達しなくなった場合は、自分のテンプレートの中に
`systemctl enable systemd-networkd`があるか確認してください。

残っている唯一のホストレベルのインバウンド転送は
[カスタムポートマッピング](#カスタムポートマッピングとアウトバウンドアクセス)です —
完全に任意で、所有者が管理し、同じ`Port=tcp:<host-port>:<container-port>`の`.nspawn`
の仕組み（これは`systemd-nspawn`が起動時にDNATルールとして自動的にセットアップし続け
ます）を使います。

具体的に、これを完了させるには：

1. `sudo systemctl enable --now systemd-networkd`（**Network diagnostics**にはこの
   ためのチェックとワンクリック修正があります）、そして
   `sudo sysctl -w net.ipv4.ip_forward=1`（`/etc/sysctl.d/`配下で永続化してください）
   — `nspawnbr0`自身の`.network`ファイル（上記参照）内の`IPMasquerade=yes`はNATルールを
   追加しますが、インターフェース間の実際のパケット転送は、このパッケージがあなたの
   代わりに有効化しない、別個のカーネル全体の設定です。NetworkManager/ifupdownがすでに
   メインのNICを管理している場合は、`nspawnbr0`には触れないよう伝えてください
   （例えばNetworkManager.confの`unmanaged-devices=interface-name:nspawnbr0`）。
   そうすればnetworkdがそれを自由に管理できます。
2. コンテナの**テンプレート**内で、ベイクする前に（[§2](#2-ホストの前提条件)の
   `openssh-server`のベイクと同じステップ）：`systemctl enable systemd-networkd`を
   実行し、`host0`が実際にブリッジからDHCP設定を受け取るようにしてください —
   `debootstrap`の出力はデフォルトではこれを有効化しません。**任意ではなく必須**です：
   これをスキップすると、そのテンプレートからのコンテナは決してBOOTINGから抜け出しません。
3. コンテナを起動（または再起動）します — `machinectl start`がそのvethを`nspawnbr0`に
   従属させ、ブリッジからDHCP経由でアドレスとルートを取得し、nspawnmgr/`guacd`が今や
   直接それに到達できるようになります。

### 名前によるコンテナの解決

管理対象のコンテナはすでにIP経由で互いに到達できます（nspawnmgr自身のファイアウォール
セットアップの中には、コンテナ間の`FORWARD`トラフィックをブロックするものは何も
ありません — `NSPAWNMGR-OUTBOUND`チェーンのDROPルールは、宛先にかかわらず、コンテナ
*自身の*アウトバウンドパケットにのみマッチします）。このセクションなしで欠けているのは、
DHCPによってコンテナごとに割り当てられ、再起動をまたいで変わりうる内部アドレスの代わりに、
名前でピアを検索する方法です。

`dnsmasq`はこのパッケージの本物の`apt`依存関係です（バンドルされているguacd/Tomcatとは
異なります — [§2](#2-ホストの前提条件)参照；`dnsmasq`のhostsファイル提供の振る舞いは
バージョン間で十分にシンプルかつ安定しているため、特定のバージョンに固定する必要は
ありません）。自動的にインストール・設定されます：`nspawnbr0`にのみバインドされ
（ホスト自身のLAN/アップリンクインターフェースからは決して到達できません — これは
オープンリゾルバではなく、決してそうなってはなりません）、`/etc/nspawnmgr/dns-hosts`
にあるものを提供します。すべてのコンテナも、自動的に`nspawnbr0`自身のアドレス
（`10.100.0.1`）を自身のDNSサーバーとして得ます。これは`nspawnbr0`の`.network`ファイル
から直接届き、追加の管理者ステップは不要です。nspawnmgrは、現在`RUNNING`のすべての
MANAGEDコンテナ自身の名前と内部アドレスから、`/etc/nspawnmgr/dns-hosts`
（`ContainerDnsSyncService`、約15秒ごと）を再生成します — これは`guacd`/レディネスが
すでに解決しているのと同じアドレスであるため（上記参照）、新たに発見すべきものは何も
ありません。dnsmasqは変更された`addn-hosts`ファイルを自発的には検知しません
（それについての自動的/inotifyベースのリロードはなく、SIGHUPまたは再起動のみです）。
そのため、すべての書き込みの後にリロードが続きます
（`nspawnmgr-reload-dnsmasq.sh`/`DnsReloader`）— これがないと、ディスク上のファイルが
どれだけ最新であっても、コンテナは互いの名前解決に失敗し続けてしまいます。

このdnsmasqインスタンスはホスト上で直接実行されるため、デフォルトでホスト自身の
`/etc/hosts`もコンテナに読み取り・提供します（実地で望まれる挙動として確認済み）—
そこにある管理者自身の静的なLANエントリ（例えば`192.168.1.15 acer`）は、ホスト自身から
だけでなく、すべてのコンテナの内側からも解決可能になります。1つの注意点：もし
`/etc/hosts`がホストの裸のホスト名をループバックアドレスにもマッピングしており
（Debian自身の`127.0.1.1 <hostname>`という規約）、*かつ*その同じ裸の名前が下記の外部
ホスト名設定として設定されている場合、2つのソースが衝突し、dnsmasqはどちらのアドレスで
も答えてしまう可能性があります — その設定には、すでに`/etc/hosts`にマッピングされている
短い名前を選ばないようにしてください。

`/etc/nspawnmgr/dns-hosts`はさらにもう1つ、固定のエントリを持ちます：ホスト自身の外部
ホスト名（`nspawnmgr.host.external-hostname`/`HOST_EXTERNAL_HOSTNAME` —
`setup-sudo-account.sh`によってインストール時に自動的に検出され、その後は
[`/admin/settings`](#ライブ編集可能な設定adminsettings)でライブ編集可能です）で、
`nspawnbr0`自身の固定アドレス（`10.100.0.1`）を指します。コンテナには他にホストへ戻る
経路がまったくありません — これにより、ホストが転送し返すあらゆるもの
（例えば[カスタムポートマッピング](#カスタムポートマッピングとアウトバウンドアクセス)）
に到達するために、ホスト自身の名前を解決できるようになります。上記のコンテナエントリと
同じ方法・同じスケジュールで同期されます；これがまだ未設定の`localhost`のデフォルトの
ままである間は完全に省略されます（「localhost」自体を`10.100.0.1`にマッピングすることは、
単に役に立たないだけでなく、積極的に間違っているためです）。

このdnsmasqインスタンスは同時に、`.internal`名だけでなく、すべてのコンテナにとって
*唯一*のDNSサーバーでもあります — そのため、`.internal`の外側にあるものはすべて、設定
済みの上流リゾルバー`nspawnmgr.dns.upstream-servers`（デフォルトは`1.1.1.1,9.9.9.9`、
[`/admin/settings`](#ライブ編集可能な設定adminsettings)でライブ編集可能）にも転送
します — 例えばコンテナを社内のDNSサーバーに向けるためです。何らかの上流が設定されて
いないと、コンテナ自身の`dnf`/`pacman`/`apt`（実際のパッケージミラーからフェッチする）
や、本物のインターネットホスト名を必要とする他の何かは、「Could not resolve host」で
完全に失敗します — 実地で確認済み。それでも上記の意味でのオープンリゾルバではありません
：転送はホスト自身の通常のインターネット経路を通じて行われ、dnsmasq自体は依然として
`nspawnbr0`にのみバインドされており、コンテナブリッジの外側からは到達できません。

上流サーバーは、上記のメインの`nspawnmgr.conf`とは別の、独自のファイル
`/etc/dnsmasq.d/nspawnmgr-upstream.conf`にあります — dnsmasq自身の
`conf-dir=/etc/dnsmasq.d/`（Debianのデフォルトの`/etc/dnsmasq.conf`）によって、それと
一緒に自動的にインクルードされます。追加のディレクティブは不要です。
`ContainerDnsSyncService`は、`dns-hosts`を稼働中のコンテナと同期させておくのと同じ方法
（約15秒ごとにポーリングし、実際の実効値が変化したときにのみ書き換えます）で、これを
現在の設定と同期させ続けます。`postinst`は初回インストール時（ファイルがまだ存在しない
場合のみ）に、同じ`1.1.1.1`/`9.9.9.9`のデフォルトでこれをシードします。そのため、
nspawnmgr自体がまだ立ち上がってそれを同期する引き継ぎを行う前の、まさに最初の起動から
上流の名前解決が機能します。

コンテナは互いを、裸のnspawnmgr名（`b1`）で、または固定の`.internal`サフィックス配下の
FQDN（`b1.internal`）で解決します — dnsmasqの`domain=`/`expand-hosts`オプションが、
別個の設定なしで、同じ`dns-hosts`エントリから両方の形式を提供します。`internal`は
IANAがまさにこの目的のために予約している特別用途のTLDです（RFC 8375、`home.arpa`と
同じカテゴリです）。でっち上げのドメインではなく、実在する公開のものと決して衝突しない
ことが保証されています。範囲はMANAGEDコンテナのみです（EXTERNALな、管理者が設定した
ホストはすでに独自の`hostname`を持っており、ここには追加されません）。そしてその名前
空間はそれらすべてにわたってフラットです — これは純粋にネットワークレベルの到達可能性
であり、あるユーザーがウェブUI上でどのコンテナを見たり接続したりできるかとは独立して
います（Machinesグリッドは、ユーザーが所有するか共有されたマシンだけを表示します。
ただし管理者は所有権にかかわらずすべてを見ます）。

これがエンドツーエンドで機能するには、さらに2つの要素が必要です：

- **コンテナ側**：`systemd-resolved`は、リンクに修飾するためのルーティング/検索ドメイン
  が設定されていない限り、`b2`のような非修飾（ドットなし）の名前を実際のDNSサーバーへ
  送ることを一切拒否します — LLMNR/mDNSへのみ送ります。DHCPがこれを提供することも
  できますが、それにはコンテナ自身の`80-container-host0.network`（`systemd-nspawn`自体
  が生成するもので、このテンプレートが制御するものではありません）が
  `UseDomains=yes`でオプトインする必要があり、デフォルトではそうなっていません。
  テンプレートは代わりに、
  `/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf`
  （`[Network]\nDomains=internal`）に静的なドロップインを出荷します。これはsystemdの
  ユニットドロップインと同じ方法でファイル名によってマージされます — DHCPを完全に
  回避し、何らかのオプションが実際に送られてくることに依存しません。
- **dnsmasq側**：`domain=`/`expand-hosts`だけでは、dnsmasqが*自身の回答を装飾する*
  サフィックスを制御するだけです — それらは、すでに*到着した*クエリ（上記のルーティング
  ドメインを持つコンテナが今送っているまさにそのもの）に対して権威を持たせるわけでは
  ありません。`local=/internal/`も設定しない限り、着信する`b2.internal`クエリは
  hosts/`addn-hosts`のマッチングを完全にすり抜け、他の名前と同様に上流へ転送されて
  しまいます — `.internal`は公開されては存在しないため、それは単に失敗します
  （そうでなければ、設定されているどの公開リゾルバにもコンテナ名を漏らしてしまいます）。
  `local=/internal/`は`.internal`をdnsmasq自身の権威ゾーンとしてマークします：自身の
  hostsデータからのみ回答し、本当に未知のものには`NXDOMAIN`を返し、決して転送しません。

もしどちらかのdnsmasqファイルを稼働中のホスト上で直接手編集する場合：`domain=`、
`expand-hosts`、`local=`（`nspawnmgr.conf`内）、そして`server=`
（`nspawnmgr-upstream.conf`内）はすべて構造的なものです — dnsmasqはこれらをプロセスの
起動時にのみパースします（実地で確認済み）— これは、`DnsReloader.reload()`/
`nspawnmgr-reload-dnsmasq.sh`が`SIGHUP`経由で正しくホットリロードする`addn-hosts`とは
対照的です。構造的なもののいずれかを手編集した後の単純な`systemctl reload dnsmasq`は
効果がありません；`systemctl restart dnsmasq`を使ってください。`ContainerDnsSyncService`
はすでにこの区別を認識しています：`addn-hosts`の変更は上記のように
`DnsReloader.reload()`（SIGHUP）を経由しますが、上流サーバーの変更は別個の
`DnsReloader.restart()`/`nspawnmgr-restart-dnsmasq.sh`（完全な`systemctl restart`）を
経由します — その1つに`reload()`を使うと、ディスク上のファイルは正しいままなのに、
dnsmasqは自身が最後に実際に起動したときの内容で黙って答え続けてしまいます。通常の
パッケージのインストール/アップグレードはどちらも必要ありません：`.deb`のpostinstは
`nspawnmgr.conf`を（再）インストールするたびに常に独自の完全な`restart`を発行します。

### nspawnmgrの外部で作成されたマシンの検出

マシンがホスト上で直接手作業で作成された場合 — 自分で実行した`machinectl clone`/
`debootstrap`/`import-tar`、またはバックアップから復元されたイメージ — nspawnmgrは、
管理者がコンテナ一覧で**Discover machines**をクリックするまで、それが存在することを
まったく知りません。これは、`machinectl`が現在認識しているすべてのイメージ名をnspawnmgr
自身のデータベースと比較し、まだ追跡されていないものを**発見を実行した管理者の所有物と
して**、通常のMANAGEDコンテナとして登録します。再実行しても安全です — すでに（名前で）
追跡されているものはスキップされます。

Discoveryはマシンの存在を登録し、それを起動/停止/削除したり、（上記のように）名前で
解決されたりできるようにします。これは、nspawnmgr経由でコンテナを作成する場合とは
異なり、SSH/RDP/VNC管理者アカウントを意図的に決してインストールしません — nspawnmgr
自体がプロビジョニングしたコンテナとは異なり、手作業で構築されたイメージの内部にすでに
何が存在するかを知る方法がないため、3つのいずれについてもアカウント名を仮定したり
`useradd`/サーバーのインストールを実行したりすることは決してありません。実際に
*行うこと*：各マシンを登録した直後に、SSH（ポート22）、RDP（ポート3389）、または
VNC（ポート5900）がすでにリッスンしているかをチェックし、そうであれば自動的にそれに
対するGuacamole接続を配線します — 下記のHostsページが使うのと同じ仕組みである
**プロンプト資格情報**モードです。そのため、nspawnmgrが1つを生成・保存するのではなく、
接続するたびにユーザー名/パスワードを尋ねられます。もしそれらのポートのいずれも発見
時点でまだ開いていなかった場合（または後でボックス上で1つ有効化した場合）は、代わりに
コンテナ自身の詳細ページから手作業で行ってください — 下記の「Remote access」参照。

### nspawnmgr自身がセットアップしていないコンテナへのリモートアクセス

コンテナの詳細ページには、nspawnmgrがそのプロトコルについて生成済みの資格情報を
持っていない場合はいつでも、SSH、RDP、VNCそれぞれについて**Remote access**セクションが
あります — 検出されたコンテナでは常に真であり、通常のnspawnmgrが作成したコンテナでも、
作成時にRDP/VNCが辞退された場合は真です。**Enable SSH/RDP/VNC access**をクリックすると、
そのポートが実際に今リッスンしているかがチェックされ、そうである場合にのみ、Discovery
自身の上記の自動配線ステップとまったく同じように、プロンプト資格情報のGuacamole接続が
配線されます；**Disable**は再びそれを削除します。このチェックは、Enableをクリックした
瞬間に一度だけ行われます — その後コンテナ内のサービスが再び停止しても、nspawnmgrが
バックグラウンドですべてのコンテナを継続的に再プローブするのではなく、次回の接続失敗
まではConnectボタンは有効なままです。

このセクションは、nspawnmgrがすでに本物の生成済み資格情報で管理しているプロトコル
（すべてのコンテナのSSH、そして作成時にリクエストされた場合のRDP）については意図的に
決して提供されません — その接続は完全にそのまま放置され、この機能が動作している生成済み
資格情報をプロンプト資格情報の接続で黙って置き換えることは決してできません。

### ホスト：管理者が管理する外部マシン

**Host**は、まったくnspawnmgrが管理するコンテナではないネットワーク上の任意のマシン向け
のエントリです — 既存のWindowsマシン、NAS、他チームのサーバー、ここにある他のすべてと
同じGuacamole SSOフローを通じてアクセスするのに便利な、SSH/RDP/VNCで到達可能な何でも
かまいません。別個のHostsページはありません：Hostは内部的には`Container`行です
（種類は`EXTERNAL`）。そのため、これは通常のカードとして表示されます — バックエンド
バッジの代わりに固定の`HOST`バッジがつきます — 他のすべてのマシンと同じメインの
**Machines**グリッド上に、nspawn/podman/QEMUマシンと並んで表示され、その詳細ページは
他のすべてのマシンが使うのと同じ`/containers/{id}`ルートです。管理者は「+」メニューの
**New Host**項目（`/admin/hosts/new`、管理者専用）から1つを追加します：名前、
ホスト名/IP、所有者のユーザー名（すでに少なくとも一度はログインしたことのあるユーザーに
属している必要があります）、そしてSSH/RDP/VNCのうちどれを提供するかとそれぞれのポート
です。そのホスト自身の詳細ページを見ている管理者は、Manageパネルに**Edit host**
（同じフォームへ、`/admin/hosts/{id}/edit`で）と**Delete host**ボタンを得ます —
別個のhosts一覧ページはありません；データベースが唯一の信頼できる情報源です。

**可視性は、他のすべてのマシンと同じ所有者/管理者/共有ルールに従います** — Hostは
管理者が作成したからといって公開されているわけではありません；管理者、その所有者、
または明示的に共有された相手だけが、自分自身のMachinesグリッドでそれを見ます
（`ContainerRepository.findVisibleToUserOrderByName`は、nspawn、podman、QEMU、Host
の行すべてに対して均一にこれを適用します）。

**RUNNING/STOPPEDはライブに解決され、保存されません。** nspawnmgrはHostのライフサイクル
をまったく制御しないため、その状態バッジは、設定済みのSSH/RDP/VNCポートのうち有効化
されているものに対する単一のTCP到達可能性チェック（`HostLivenessService`）から得られます
— 存在する場合はまずSSH、次にRDP、次にVNCの順です — ホストごとに1分間キャッシュされ、
Machinesグリッドとホスト自身の詳細ページのそれぞれがリクエストのたびに新しいプローブを
発生させることはありません。3つのいずれも有効化していないHostにはプローブするものが
何もなく、常にRUNNINGを表示します。

接続は常にライブで資格情報の入力を求めます — nspawnmgrはホストのパスワードを決して
保存しません。これはDiscovery自身の自動配線とコンテナごとのRemote accessセクションが
どちらも上記で使っているのと同じプロンプト資格情報の仕組みです。

hostname/IPフィールドは、単なるアドレスだけでなく本物のホスト名にもできます —
セルフホスト型のインストールでは、Guacamole自身のSSH/RDP/VNCクライアントはセルフホスト
型の`nspawnmgr`コンテナの内側で動作し、その唯一のDNS経路はnspawnmgr自身のdnsmasq
（コンテナ名と公開の上流リゾルバー）で、プライベートLAN自体の名前解決への可視性は
ありません。これを回避するため、nspawnmgrは（他のすべての権限を要する操作に使うのと
同じsudo権限を持つSSHアカウント経由で）誰かが接続するたびに、基盤となるホスト上で
そのホスト名を自分で再解決し、ホスト名の代わりに解決済みのアドレスを直接Guacamoleに
渡します — そのため、あなたのネットワーク自体のDNS/NetBIOS/mDNSしか知らないLAN専用の
名前でも引き続き機能し、DHCPで再割り当てされたアドレスも、管理者が気づいてエントリを
再保存する必要なく、次回の接続時に自動的に拾われます。接続時点でホスト名がホスト上で
解決されない場合、接続の試みは古いアドレスのまま進行するのではなく、明確なエラーで
失敗します。

共有はコンテナと同じように機能します：所有者は、そのエントリ自身の詳細ページから他に
誰が接続できるかを管理します。所有者ではない管理者は、代わりにそこのManage配下に
**Take ownership**ボタンを見ます — 所有者がすでに離れてしまったHost（または任意の
マシン）を、データベースアクセスを必要とせずに引き継ぐのに便利です。

MachinesグリッドとHost自身の詳細ページの両方にあるSSH/RDP/VNCボタンは、離れて
ナビゲートするのではなく、新しいブラウザタブでGuacamoleセッションを開きます — 同じ
ページから複数のマシンに接続する際に便利です。Hostのカードから1つを開くと
`/hosts/{name}/session/{protocol}`が使われます。これは、通常のマシンの
`/containers/{name}/session/{protocol}`とは別個の、独自のURL名前空間です — 上記のとおり
Hostは内部的にはContainer行ですが、ユーザーが実際にブラウザで見る*セッション*URLは、
管理者の視点からするとコンテナではない何かに対して「containers」とは意図的に言いません。
両方のルートは背後で同一のテンプレート/JSをレンダリングします（iframeと、同じ
`/api/containers/{id}/session/{protocol}` APIエンドポイントへのfetch）；ページのURLだけが
異なります。どちらも数値のidではなく、マシンの**名前**をキーにしています — これは、
共有されたリンクやブラウザの履歴の中でURLが意味を持ち続けるようにするための、意図的な
選択です。

### カスタムポートマッピングとアウトバウンドアクセス

上記のSSH/RDPを超えて、コンテナの**所有者**は自身の詳細ページから、もう2つのことを
セルフサービスできます — どちらも管理者のアクションは不要です：

- **カスタムインバウンドポートマッピング**：追加のTCPまたはUDPのホストポート →
  コンテナポートの転送で、所有者が両方のポート番号を正確に選びます。nspawnmgrは、
  要求されたホストポートが別のカスタムマッピングによってすでにバインドされていないか
  を、受け入れる前にチェックします。マッピングは即座に`.nspawn`ファイルに書き込まれ
  ますが、コンテナが次回（再）起動されたときにのみ有効になります — 稼働中のコンテナに
  1つ追加すると、自動的に再起動するのではなく、「再起動が必要」という通知が表示されます。
- **アウトバウンドのインターネットアクセスのトグル**：上記のホスト全体での、
  オールオアナッシングのマスカレード設定とは異なり、各コンテナは個別にアウトバウンド
  アクセスをブロックできます。nspawnmgrはこれを、専用の`NSPAWNMGR-OUTBOUND`
  iptablesチェーン（初めて必要になったときに自動的に作成され、`FORWARD`の先頭から
  ジャンプされます）で自身管理します。アウトバウンドが無効化されたコンテナごとに1つの
  `DROP`ルールを保持し、そのコンテナの実際のホスト側vethインターフェースをキーとします
  — nspawnmgrは（vethのピアのifindex経由で）毎回これを動的に検索します。上記のとおり、
  veth名はコンテナの名前から予測可能な文字列として導出されるものではないためです。
  これを切り替えると、稼働中のコンテナに対して再起動なしで即座に効果を持ちます。
- **アウトバウンドの許可リスト**：アウトバウンドアクセスが無効化されている間も、
  所有者は特定の宛先 — リテラルなIPv4アドレス、ポート、プロトコル（TCP/UDP） — を
  それでも通すことができます — 例えば`127.0.0.1`で、コンテナに一般的なインターネット
  アクセスを付与することなく、同じホストに配置された別のコンテナ/サービスに到達させる
  ためです。これは同じ`NSPAWNMGR-OUTBOUND`チェーン内で、コンテナのDROPルールより前に
  ACCEPTルールとして実装されます；変更のたびに、その場でパッチするのではなく、その
  コンテナのルールをゼロからフラッシュして再構築します。アウトバウンドアクセスが有効化
  されている間は効果がありません — その場合はすでにすべてに到達可能だからです。これも
  即座に効果を持ち、再起動は不要です。

どちらも、[§3](#3-sudo権限を持つsshアカウント)のsudo権限を持つアカウント経由で
`iptables`コマンドが利用可能で、かつパスワードなしで使用可能である必要があります —
nspawnmgrがすでに`.nspawn`ファイルの書き込みやコンテナの起動/停止に使っているのと同じ
アカウントと仕組みです。

## 3. sudo権限を持つSSHアカウント

同じホスト上に、範囲を限定したsudoアクセスを持つ専用のローカルアカウントを作成して
ください。nspawnmgrはこれに（常にループバック、`127.0.0.1`経由で）SSH接続し、実際に
`machinectl`/`systemd-run`を実行し、root所有のパスに触れます。**推奨：**
`packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh`にこれをやらせてください —
これは`.deb`のpostinstが実行するのと同じスクリプトですが、パッケージをビルドも
インストールもせずに、完全にスタンドアロンで実行可能です：

```bash
sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
```

このリポジトリのチェックアウトから実行すると（フラグは不要です — 自身の隣にある
`privileged-scripts/`と`debian/nspawnmgr.sudoers`を自動検出します）、`nspawnmgr_exec`
システムアカウントを作成し、そのためのランダムなパスワードを生成・保存し、SSHキーペア
を生成し、下記で参照されるラッパースクリプトを`/usr/lib/nspawnmgr/privileged/`に
インストールし、sudoersの許可をインストール・検証し、ホストがグローバルにそれを無効化
している場合はそのアカウントのためのsshd `PasswordAuthentication`の例外を追加します。
これは冪等です — アップグレード後の再実行や、更新されたラッパースクリプトを取り込む
ために安全に再実行できます。完全な詳細については、スクリプト自身のヘッダーコメントを
参照してください。

代わりにこれを完全に手作業でセットアップしたい場合（例えば別のアカウント名を使う場合）
は、参考としてこのスクリプト自体が何をするかを見てください — ただし下記の2つの権限
階層に注意してください。ブランケットな（すべてのコマンドを、常にパスワード経由で許可
する）`usermod -aG sudo`は、nspawnmgrが実際にこのアカウントをどう呼び出すかにもはや
一致しません。

### 2つの権限階層

このアカウントのsudoersアクセスは、1つではなく2つの階層に分割されています：

- **NOPASSWD** — 固定形状の、常に安全なコマンド：`machinectl start/poweroff/terminate/
  reboot/remove/show`、`systemd-run --machine=... --pipe --quiet --wait /bin/sh -s`
  （保存済みのコンテナスクリプトの実行 — この特定の`systemd-run`の形がNOPASSWDである
  一方、下記の一般的なものはそうではない理由については下記の「信頼境界：コンテナ
  スクリプト」参照）、そして`.nspawn`設定の書き込み、コンテナのファイルの削除、
  アウトバウンドファイアウォールの同期を扱う`/usr/lib/nspawnmgr/privileged/`配下の
  ラッパースクリプトです。これらは日常的な、所有者が起動するアクション（コンテナの
  起動、ポートマッピングの編集、削除、書いたスクリプトの実行）であり、下記のどの
  コンテナ作成モードがアクティブであっても、管理者を待ってブロックすることは決して
  あってはなりません。
- **パスワード必須**（`NOPASSWD`タグなし） — `systemd-run --machine=... --pipe --quiet
  --wait`（新しいコンテナ内でrootとして任意のテンプレート作成者由来のコンテンツを
  実行します — 下記の「信頼境界」参照）、`nspawnmgr-clone-template.sh`ラッパー、そして
  `nspawnmgr-create-debian-template.sh`ラッパー（本物のDebian rootfsをダウンロード/
  展開します — §2の「コンテナテンプレート」、Templates管理ページの「Set up
  debian-minimal」ボタン参照）です。この3つはすべて作成時のみです — 最初の2つは
  `ProvisioningService`からコンテナごとにちょうど1回だけ呼び出され、3つ目はテンプレート
  がまだ存在しない場合に管理者からオンデマンドでのみ呼び出されます。どのパスワードが
  使われるか — そして管理者の関与なしにそもそも1つが利用可能かどうか — は、下記の
  モードに依存します。

すべての権限を要するコマンドは、これら2つの固定引数のラッパースクリプトまたは
`machinectl`/`systemd-run`呼び出しのいずれかを経由します — nspawnmgrは決してsudoに
任意のインラインスクリプトを実行するよう求めません。これはまさに、上記のsudoers
の許可が、スクリプトのテキストをワイルドカードマッチしなければならない（これは脆弱
です：スクリプトの内容への将来のあらゆる変更が、黙って許可を無効化する — または
黙って過度に広げてしまう — 可能性があります）のではなく、正確なコマンド/パスに
マッチできるようにするためです。

### コンテナ作成モード：保存済みシークレット vs 管理者承認

コンテナの作成が完全にセルフサービスであるか、管理者の承認を必要とするかは、
`nspawnmgr.ssh.password`/`SSH_PASSWORD`が設定されているかどうかから**導出**されます
— 別個のトグルはありません：

- **保存済みシークレット/セルフサービスモード**（パスワードが設定されている、`.deb`の
  デフォルト）：所有者の「コンテナを作成」リクエストは、この機能が存在する前と同じ
  ように、即座に自動的にプロビジョニングされます。
- **管理者承認モード**（パスワードが空欄）：新しいコンテナは、すぐにプロビジョニング
  されるのではなく、`PENDING_APPROVAL`状態になります。**Requests**ページ
  （`/requests` — そのサイドバーのナビ項目は、このモードがアクティブな間だけ、誰に
  対しても表示されます）は、これを保留中のコンテナ内ユーザーアカウントリクエストと
  並んで、1つの統合ビューにリストします。管理者はすべてのユーザーからのすべての保留中
  の項目を見て、それに対して操作できます；非管理者は自分自身のものだけを見て、
  それらを**Deny**できます（終端の`DENIED`状態に移り、SSHは一切試みられません）が、
  **Approve**はできません — 承認にはsudoパスワードが必要で、それはインラインで
  提供され、その1つの項目の作成時ステップのためだけに使われ、メモリ上に保持され、
  その実行が完了すると即座にゼロクリアされ、決して永続化されません — 意図的に管理者
  にのみ求められます。

SSHのトランスポートログインとsudoパスワードは同じ設定値を共有するため、管理者承認
モードを選ぶために`SSH_PASSWORD`を空にすると、（上記のNOPASSWD階層についてすら）
SSHセッション自体に認証するものが何もなくなってしまいます。**そのため管理者承認
モードは、`nspawnmgr.ssh.private-key-path`/`SSH_PRIVATE_KEY_PATH`が設定されている
ことを要求します**。そうすればSSHトランスポート認証は（今は空になった）パスワードの
代わりに鍵を使います。`setup-sudo-account.sh`はモードにかかわらずこの鍵を無条件に
生成するため、後でモードを切り替えるのは本当に1つの環境変数を空にする/設定して
再起動するだけです — 他に何もセットアップする必要はありません。nspawnmgrは、
パスワードも秘密鍵もまったく設定されていない場合、最初のコンテナ操作で紛らわしい
接続失敗として表面化するのではなく、起動に失敗します（`SshPropertiesValidator`）。

### 管理者/ユーザーのロール

上記の承認ページをゲートするには、ユーザーのロール（`USER`/`ADMIN`）が必要です。
これも設定値が設定されているかどうかによって選ばれる2つのモードがあります — 今回は
`nspawnmgr.auth.user-is-admin-json`です：

- **アプリ管理**（デフォルト、空欄）：**最初にログインしたユーザー**が自動的に
  `ADMIN`に昇格します；それ以外の全員はデフォルトで`USER`になります。それ以降は、
  任意の管理者が`/admin/users`で他の任意のユーザーを昇格/降格できます。ロールは
  固定的です — ログイン時に黙って再計算されることは決してありません。
- **外部管理**（`nspawnmgr.auth.user-is-admin-json`が、`auth.war`がすでに返している
  同じアイデンティティJSONへのJsonPathに設定されている場合。`user-id-json`/
  `user-username-json`などと並びます）：ロールはログインのたびにそのJSONから新しく
  再計算されます — 昇格も降格もどちらも — そして手動での付与/取り消しページは
  変更を完全に拒否します。このモードでは外部のアイデンティティソースが権威となる
  ためです。

### 信頼境界：テンプレート作成者によるプロビジョニングコマンド

上記のパスワード必須階層は、コンテナ内でrootとして`systemd-run`にコンテンツを
実行させます。そのコンテンツは常に次のいずれかから来ます：`ProvisioningService`自体
の中のリテラルな文字列、または`Template.installSshCommand`/`installXrdpCommand`です。
テンプレートは`/admin/templates`を通じて編集可能で、別個の承認ワークフローではなく、
`/admin/**`に対する既存のADMINロールによってゲートされています。言い換えると：
**ADMINロールを持つ者は誰でも、自分が編集するテンプレートから作成されるすべての
コンテナ内でrootとして何が実行されるかを、事実上制御します。** アプリ管理のロール
モードでは、現在の任意の管理者が`/admin/users`で、追加の承認ステップなしに、
セルフサービスで他の誰にでもADMINを付与できます。通常の（非管理者の）ログイン
済みユーザーは、これにまったく到達できません — `/admin/**`の外に公開されているのは
`GET /api/templates`（アクティブなテンプレート、サマリーのみ）だけです。

### 信頼境界：コンテナスクリプト

コンテナの所有者（またはそのコンテナが共有されている誰でも — コンテナ詳細ページの
「Shared with」参照）は、`/containers/{id}/scripts`経由で、名前付きのスクリプトを
定義し、それを同じコンテナ内でrootとして実行できます。これは上記のテンプレート編集
とは異なる信頼の形です：作成者はそのコンテナ自身の所有者/共有ユーザーであり、
スクリプトは決して**その1つのコンテナ**の内側だけで実行され、他の誰のものでもありません。
それらのユーザーは、自分自身のGuacamole SSHセッションを通じて、そのまさに正確な
コンテナに対する完全な対話的root shellアクセスをすでに持っています — この機能を
通じて保存済みのスクリプトを実行しても、彼らがすでに持っていなかった権限は何も
付与されません；純粋に利便性です（名前付きで、再利用可能で、毎回SSH経由で入力し
直す代わりにワンクリックです）。これが、スクリプトの実行が（
`/usr/bin/systemd-run --machine=* --pipe --quiet --wait /bin/sh -s`、固定形状で、
まさにその正確なコマンドのみ）NOPASSWDである理由です。他の人々のコンテナの内側で
実行され、コンテナ自身の所有者ではなく管理者によって作成される上記のテンプレート
作成者由来のコンテンツとは異なります。

**「Shared with」はセッションアクセス以上のものを付与します。** コンテナを共有すると、
相手のユーザーにGuacamole SSH/RDPセッション*と*、そのコンテナのスクリプトを作成・
編集・削除・実行する能力（事実上、完全なrootアクセスです — 上記参照）の両方が付与
されます；一方だけを付与する別個のトグルはありません。純粋にリモートデスクトップの
利便性のためだけに人々とコンテナを共有したことがある場合、彼らにはスクリプトの権限も
あります。

### その他のセットアップ上の注意

- このアカウントは、`TEMPLATES_DIR`をどこに向けたとしても、そこへの読み書きアクセス
  も必要とします。
- これは設計上ループバック専用であるため、nspawnmgrはこの接続について
  `strict-host-key-checking: false`をデフォルトとします。これを有効にするのは、
  ループバック以外のホストを指す場合のみにし、Tomcatアカウントが対象について事前に
  populate済みの`~/.ssh/known_hosts`を持っていることを確認してください。
- **これはすべて、nspawnmgrが自分自身が実行されているのと同じホスト上でコンテナを
  管理することを前提としています**（`.deb`が唯一サポートする構成です）。代わりに
  `nspawnmgr.ssh.host`を別のホストに向けることは、手動で設定する、ツールがサポート
  しないシナリオです：そのリモートホスト上で、このセクションのアカウント/sudoers/
  キーペアのセットアップを自分で独立して繰り返す必要があります。
- **`nspawnmgr_exec`のSSHアクセスは設計上ループバック専用です** — この資格情報を
  このホストの外部の何かに渡さないでください。外部のCI/CDパイプラインがコンテナ
  テンプレートをインストール/更新できるようにしたい場合は、代わりに別個の、意図的に
  より狭い`nspawnmgr_ci`アカウントを使ってください（上記の「CI/CDパイプラインからの
  テンプレートのインストール/更新」参照）— これは、`nspawnmgr_exec`の広範な
  NOPASSWD/PASSWORDアクセスとは異なり、正確に1つの固定形状の許可だけを持つ独自の
  sudoersファイルに隔離されており、ネットワーク越しに到達されることを想定しています。

このアカウントのユーザー名/パスワード（または秘密鍵）は、nspawnmgr自身の設定に
[§9](#9-nspawnmgrの設定)で`nspawnmgr.ssh.*`（または
`SSH_USERNAME`/`SSH_PASSWORD`/`SSH_PRIVATE_KEY_PATH`）として組み込みます。

## 4. データベース

MySQL、MariaDB、またはPostgreSQL — H2は選択肢にありません。H2は開発スタック/CI
テストハーネスの内部でのみ使われます（インメモリデータベースで、そのJVMが停止した
瞬間に消えます）；これはサポートされるデプロイ対象になったことは一度もなく、それを
1つとして選択できるコードパスはもう残っていません。MySQLとMariaDBは同じJDBCドライバ、
スキーマ、Flywayマイグレーションの場所を共有します — 一方を他方より選ぶことで変わる
のは、ウィザードがデフォルトでどのマシン名を使うか（下記）だけで、どのコードパスが
実行されるかではありません。`spring.datasource.url`と
`spring.flyway.locations: classpath:db/migration/<vendor>`は一致していなければ
なりません（環境変数リファレンスの`DB_VENDOR`参照 — 常に`mysql`または`postgresql`
であり、`mariadb`ではありません）。Flywayは起動時に自動的にマイグレーションを実行
します；`spring.jpa.hibernate.ddl-auto`は`validate`であり、`update`では決してありません
— スキーマは完全にFlywayの責任です。

データベースは、nspawnmgr自体と同じ方法で**セルフホスト**されます（
[§1](#1-アーキテクチャ概要)）— 下記のウィザードは常に、既存のサーバーを指すよう
求めるのではなく、それを実行するための真新しいDebianコンテナをプロビジョニングします。

### 初回起動セットアップウィザード

最初にTomcatを起動する前に、`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_VENDOR`自身を
準備・設定する必要はありません — このウィザードがあなたの代わりにそれを行います。
これは独自のWAR（`ROOT.war`）に収められており、セルフホスト型`nspawnmgr`マシンの
内側の、Tomcatのルートコンテキストにデプロイされます
（`http://<host>:<forwarded port>/`、[§1](#1-アーキテクチャ概要)）。
`nspawnmgr.war`自体の内側ではありません：`/`にアクセスすると、動作するデータベースが
設定されていればまっすぐ`/nspawnmgr/`にリダイレクトされ、そうでなければこのウィザード
が表示されます。データベースがまだ設定されていない間に`/nspawnmgr/`に直接アクセス
すると、単に`/`に戻されます — このウィザードが常に、あなたが今どちらの状態にいるかを
決める唯一の場所です。

**データベースエンジン**（MySQL、MariaDB、またはPostgreSQL）と、任意で非デフォルトの
**データベースマシン名**（エンジンごとに`mysqldb`、`mariadb`、または`postgresdb`が
デフォルトで、編集可能です）を選んでください。さらに**初期のnspawnmgrユーザー名と
パスワード**も入力してください — セットアップが完了したらそれでログインすることに
なる、セルフホスト型`nspawnmgr`マシン自体の内側に作成される本物のLinuxアカウントです
（これがなぜ`auth.war`のPAMバックエンドが必要とするすべてであり、追加の設定が不要
なのかについては[§8](#8-authログインバックエンド)参照）。

送信すると、ウィザードは：

1. データベースマシンをプロビジョニングします（`nspawnmgr-bootstrap-db-machine.sh`、
   このアプリの他のすべての権限を要する操作が使うのと同じsudo権限を持つSSHアカウント
   経由で実行されます、[§3](#3-sudo権限を持つsshアカウント)参照）— Debianテンプレート
   を複製し、選択されたエンジンをインストールし（MySQLとMariaDBはどちらもDebian自身の
   `mariadb-server`をインストールします；Debian上には別個のOracle MySQLパッケージは
   ありません）、そのマシン内部の初回起動systemdユニットが、エンジンが本当に稼働
   している状態になった時点で（オフラインでは試みられません — どちらのエンジンも
   `CREATE DATABASE`/`CREATE USER`を実行するには実際に短時間稼働する必要があります）、
   決め打ちの`nspawnmgr`/`guacamole`データベースとユーザーを、新しく生成された
   パスワードとともに作成するのを待ちます。
2. nspawnmgr自身のFlywayマイグレーションを実行し、続いてGuacamoleのスキーマスクリプト
   を実行し（すべてのインストールは常に真新しいデータベースから始まるため、ここで
   実行すべき「スキーマがすでに存在するか」というチェックはありません）、あなたの
   代わりにGuacamoleの`guacamole-auth-jdbc`拡張を配線します（拡張のJARを
   `GUACAMOLE_HOME/extensions/`にコピーし、`<vendor>-hostname`/`-port`/`-database`/
   `-username`/`-password`のプロパティを`GUACAMOLE_HOME/guacamole.properties`に
   書き込みます — それが何のためかについては[§7](#7-guacamole)の
   「GUACAMOLE_HOMEと認証バックエンド」参照）。もしこの最後のステップが何らかの理由
   で失敗しても、それは致命的ではありません — nspawnmgr自身のデータベース
   （このウィザードが表示され続けるかどうかを実際に決めているもの）はその時点で
   すでに動作しており、失敗は単に、その1ステップを手作業で完了させるよう伝える警告
   として表面化するだけです。
3. セルフホスト型`nspawnmgr`マシン内部に、そのマシンに再び到達する同じsudo権限を
   持つアカウント経由で、初期のnspawnmgr Linuxアカウントを作成します — これは
   `ProvisioningService`がすでに通常の管理対象コンテナ自身のログインアカウントを
   作成するのに使っているのと同じ仕組みです。
4. `nspawnmgr`マシン内部の`/etc/nspawnmgr/db-config/db.properties`
   （`tomcat:tomcat`所有、`nspawnmgr-bootstrap-app-machine.sh`によって自動的に
   作成されます）に、動作するnspawnmgrの接続設定を保存します。

成功ページは、`nspawnmgr.war`とGuacamole自身のコンテキストの両方を即座にその場で
リロードします — クリックするボタンも、Tomcatの再起動も不要です —
`/opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml`と`guacamole.xml`に触れることに
よって行われます（他の権限を要する操作が使うのと同じ`nspawnmgr-write-file.sh`
ラッパーで、起動のこの時点ではまだアプリケーションコンテキストが存在しないため、
ウィザード自身のSpringを使わないSSHヘルパー経由で実行されます）；Tomcat自身の
バックグラウンドの自動デプロイスレッドが各変更に気づき、そのコンテキストをその場で
再デプロイします。`/nspawnmgr`についてはこれが起動時到達可能性チェックを再実行し、
今度こそ実際のアプリケーションを起動します。Guacamoleにも同じ処置が必要です：
真新しい起動では、管理者がこのウィザードに何かを入力する機会を得る前に、自身の
webappが起動します（そしてその時点で一度、`guacamole.properties`を読み込み/拡張を
ロードします）— ここで再デプロイもしないと、Guacamoleはデータベースバックエンドの
認証拡張がロードされないまま動き続け、このウィザード自身のスキーマステップがたった今
作成した`guacadmin`アカウントを含む、すべてのログインを拒否してしまいます。ページは
`/nspawnmgr/`をポーリングし、それが立ち上がった時点で自動的にそこへ連れて行きます —
通常は数秒であり、これがかつて必要としていた完全なTomcat再起動ではありません。

ウィザード自体は、`nspawnmgr`マシンとそのデータベースマシンの両方を、nspawnmgr自身の
コンテナ一覧に、通常の目に見えるコンテナとして登録します — ステップ3で作成された
アカウントの所有物として、それぞれ「Virtual machine management」/「Database server」
の説明とともに、マイグレーション直後の自身のデータベース処理の中で直接、最初のログイン
は不要です（同じ根底の登録の仕組みについては
[「nspawnmgrの外部で作成されたマシンの検出」](#nspawnmgrの外部で作成されたマシンの検出)
参照。通常は手作業で管理者が起動します）。（同じアカウントで）実際に初めてログイン
すると、単にウィザードがすでに作成した管理者アイデンティティに再接続されるだけです
（[§3](#管理者ユーザーのロール)）— 両方のマシンはすでにそこで待っています。それ以降
それらが隠されたり特別扱いされたりすることはありません；他のコンテナと同じように、
どちらにもSSHでき、共有でき、削除できます — もっとも、今まさにそこから実行している
`nspawnmgr`マシンを削除するのは、自明のとおり良い考えではありません。

**ウィザードのフォーム自体は未認証で、任意のホストから到達可能です。** まだデータベース
がないため、usersテーブルもなく、その背後に座るログインシステムもありません —
データベースが設定される前にこのポートに到達できる者は誰でも、それをセットアップ
できます。あなたのデプロイにとってそれが重要であれば、このポートへのネットワーク
アクセスを（§4が完了するまでファイアウォールのルールでパブリックなインターフェース
から遠ざけるなど）自分で制限してください。

## 5. nspawnmgrのインストール

ここから2つの経路があります — どちらか一方を選んでください。**オプションA（`.deb`）は
§3とほとんどの§6をあなたの代わりに行います**；オプションBは§6以降の完全に手作業の
手順です。（Arch LinuxとFedora/RHELのパッケージも存在し、オプションAと同じ自動化です
— すぐ後の[「Arch Linuxへのインストール」](#arch-linuxへのインストール)と
[「Fedora/RHEL（RPM）へのインストール」](#fedorarhelrpmへのインストール)を参照して
ください。）どちらの場合でも、§4（データベース）、§7のGuacamoleの
`GUACAMOLE_HOME`/JDBCのセットアップ、§9の設定値、§10の検証は依然としてあなた自身の
責任です — 3つのパッケージのいずれも、*sudoアカウント*と*WARをTomcatにデプロイする
こと*以上のものは自動化しません。Guacamole自身のストレージバックエンドやnspawnmgrの
アプリケーションレベルの設定は自動化されません。

**各パッケージ形式を*ビルド*するために必要なものは、それを*インストール*するために
必要なものと同じではありません** — 経路を選ぶ前に知っておく価値があります。特に
ビルドしているマシンがデプロイ先のマシンと違う場合は：

| 形式 | ビルドに必要 | インストールに必要 | クロスビルド可能？ |
|---|---|---|---|
| `.deb`（`packaging/nspawnmgr-deb/`） | JDK 21 + Maven（`jdeb`プラグインは純粋なJavaです） | `apt`、Debian/Ubuntu | **可能** — Arch/Fedora/Windows/macOSを含む、JDKを持つ任意のホストでビルドできます |
| Arch（`packaging/nspawnmgr-arch/`） | JDK 21 + Maven、**加えて`makepkg`/`base-devel`** | `pacman`、Arch Linux | **不可能** — `makepkg`はクロスプラットフォームの等価物を持たないネイティブなArchツールです；ビルドホスト自体がArch（または`archlinux/devtools`コンテナイメージ）でなければなりません |
| RPM（`packaging/nspawnmgr-rpm/`） | JDK 21 + Maven、**加えて`rpm-build`** | `dnf`、Fedora/RHEL | **不可能** — `rpm-maven-plugin`の評判にもかかわらず、これは実際に本物の`rpmbuild`バイナリにシェルアウトします；RPMでないビルドホスト（例：Windows）ではクロスプラットフォームの等価物なしに完全に失敗することが実地で確認されています。Archの`makepkg`と同じ話です |

これらをビルドするための予備のArchやFedoraマシンがない場合、
`packaging/ci/arch-runner/bootstrap-arch-runner.sh`と
`packaging/ci/fedora-runner/bootstrap-fedora-runner.sh`は、デュアルブートやベアメタル
なしでどちらかを手に入れる1つの方法を示しています：どちらも本物のrootfsを、普通の
`systemd-nspawn`コンテナ（Docker/Podmanイメージではありません — nspawnはここで最も
シンプルであることが判明しました。CIのためだけの独自のブリッジを必要とせず、
デフォルトでホストのネットワーク名前空間を共有するためです）にベイクします。
`.gitea/workflows/build.yml`の`arch-package`と`rpm-package`ジョブは、どちらかの
コンテナが存在すればその都度実行される正確なビルドコマンドを示しています
（JDK/Maven/ネイティブなパッケージングツールをインストールし、その後下記のとおり
`BUILD_ARCH_PKG=1`/`BUILD_RPM=1 tools/scripts/build-all.sh`）。

### オプションA：`.deb`パッケージ（推奨）

**ホスト**についてはDebian/Ubuntuのみです — これが作成するセルフホスト型の
`nspawnmgr`/データベースマシンは、[§1](#1-アーキテクチャ概要)のとおり、常にDebianです。
§3（sudo権限を持つアカウント、sudoers、SSHキーペア）を処理し、Tomcat、4つすべての
WAR、そして`guacd`がすでにインストールされた状態で、セルフホスト型`nspawnmgr`マシンを
作成・起動します — ただし§6の*残り*はスキップできません：特に「HTTPSを有効にする」と
「別のポートを使う」は引き続き読む価値があります（下記の「これの後にまだ手作業で
残っているもの」参照）。今はホスト上ではなく、そのマシンの内側に適用されるだけです。
インストールが終わったら§7に進んでください。

**`.deb`を入手する**方法は、自分でビルドするか：

```bash
mvn -DskipTests install                          # root -> target/nspawnmgr.war (installed, not just packaged - the next module needs it)
mvn -f auth/pom.xml -DskipTests package          # -> auth/target/auth.war
mvn -f packaging/nspawnmgr-deb/pom.xml package   # -> packaging/nspawnmgr-deb/target/nspawnmgr_*.deb
```

（または`BUILD_DEB=1 tools/scripts/build-all.sh`。これは同じ3ステップを行います —
この環境変数が存在するのは、`.deb`をビルドするには初回使用時に`jdeb` Mavenプラグインを
フェッチするためのネットワークアクセスが必要で、普通の開発ビルドがそれを強制される
べきではないためです）、またはあなたのチームがどこかに公開している事前ビルド済みの
ものをインストールするかのどちらかです — このリポジトリ自身のCI
（`.gitea/workflows/build.yml`の`publish-deb`ジョブ）は、自分のフォーク/インスタンス
向けに同じものをセットアップしたい場合の動作する参考例として、すべての成功したビルド
をGitea Debianパッケージレジストリに公開します（リポジトリのActionsシークレット
`PACKAGE_REGISTRY_TOKEN`、パッケージ書き込みスコープを持つGiteaアクセストークンが
必要です — そのジョブ自身のワークフローファイル内のコメントを参照してください）。

**それをインストールする：**

```bash
sudo apt install ./nspawnmgr_0.4.0_all.deb   # pulls in openssh-server, openssl, dnsmasq, systemd-container - not a JRE, not tomcat9
```

`tomcat9`も`guacd`/`guacamole-tomcat`も、このパッケージの`Depends:`には含まれません —
apt自身の`tomcat9`の可用性はリリースによって十分に変わり、`guacd`/`guacamole-tomcat`
はどの現行リリースにもまったくパッケージ化されていません
（`packaging/nspawnmgr-deb/debian/control`自身の注記参照）。`tomcat9`、`guacd`、
`guacamole.war`はすべて代わりにバンドルされており、あなたから何も必要としません
（§6と§7参照）— §7に残っている唯一の手作業のステップは、データベースバックエンドの
認証拡張です。これは本当にあなただけが持つ資格情報を必要とするためです。

**たった今、自動的に何が起きたか**（正確なスクリプトについては
`packaging/nspawnmgr-deb/debian/postinst`と`nspawnmgr-bootstrap-app-machine.sh`
参照）：

- `nspawnmgr_exec`システムアカウントが**ホスト**上に作成されました；そのための
  ランダムなパスワードが生成され（初回インストールのみ — アップグレードでは
  触れられません）`/etc/nspawnmgr/nspawnmgr.env`に書き込まれました（これが§3の
  「保存済みシークレット」sudoパスワードです — これが何を意味し、代わりに管理者
  承認モードに切り替える方法については§3参照）；SSHキーペアが生成され、モードに
  かかわらずそのアカウントの`authorized_keys`にインストールされました。§3の
  NOPASSWD/パスワード階層の分割 → `/etc/sudoers.d/nspawnmgr_exec`は、信頼される前に
  `visudo -cf`で検証されました。
- 共有ブリッジ（`nspawnbr0`）とdnsmasqがホスト上にセットアップされました。他の
  任意の管理対象コンテナと同じです — 上記の「名前によるコンテナの解決」参照。
- `debian-minimal`がベイクされ（`/admin/templates`の「Set up debian-minimal」が
  生成するのと同じtarballです）、`nspawnmgr`という名前の真新しいマシンに複製されました。
- まだ展開されたrootfsのままで、まだ起動していない状態で：JRE、バンドルされた
  Apache Tomcat 9.0.120のtarball、4つすべてのWAR
  （`nspawnmgr.war`/`auth.war`/`guacamole.war`/`ROOT.war`）、そして自己完結型の
  `guacd`バンドル（独自のOpenSSL 3.x、最小限のFFmpeg、FreeRDP2、libssh2）が、その
  マシン自身のファイルシステムに直接インストールされました — `tomcat`/`guacd`の
  システムユーザーがその内側に作成され、`manager`/`host-manager`/`examples`/`docs`
  のwebappが取り除かれ、`GUACAMOLE_HOME`は同じマシン自身の`guacd`を指す最小限の
  `guacamole.properties`でシードされ、`guacamole-auth-jdbc`と両方のJDBCドライバjar
  が展開されました（すべてネットワークアクセス不要 — すべてバンドル済みで、何も
  ダウンロードされません）。
- `/etc/nspawnmgr/nspawnmgr.env`の書き換えられたコピーがそのマシンに書き込まれました
  （`SSH_HOST`と`HOST_PUBLIC_ADDRESS`は`127.0.0.1`ではなく`nspawnbr0`自身のアドレスを
  指すよう再設定され、起動後にnspawnmgrがホストの`nspawnmgr_exec`アカウントへ
  外向きに到達できるようになります）、SSH秘密鍵のコピーとともに。
- 空いているホストポートが選ばれ（まず`8080`、すでに使用中のものを超えて増加します
  — インストール中に表示されます）、そのマシン自身の`:8080`へ、その`.nspawn`ファイル
  内の`Port=`行経由で転送されます。そのため`http://<このホスト>:<そのポート>/`は、
  セルフホストでないインストールが常にそうしてきたのとまったく同じようにnspawnmgrに
  到達します。
- マシンが起動されました。その内部のTomcatは、`ROOT.war`の初回起動データベース
  ウィザード（§4）を提供する状態で立ち上がります — この時点ではまだデータベースが
  設定されていません。以前と同じですが、今は異なる基盤アドレスで到達可能というだけです。

**正しく反映されたか確認する：**

```bash
sudo machinectl list                             # should show "nspawnmgr" running
sudo visudo -cf /etc/sudoers.d/nspawnmgr_exec    # should print "parsed OK"
curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port shown during install>/
```

Tomcat関連のものはもうホスト自体では何も動いていません — `tomcat9.service`や
`/opt/tomcat9`をそこで探さないでください；どちらも今は`nspawnmgr`マシンの内側に
あります（`sudo machinectl shell nspawnmgr`でその内部を見て回るか、一度ログインした
後はnspawnmgr自身のSSHアクセスを使ってください — §4のそれがコンテナ一覧に表示される
ことについての注記参照）。`.deb`は決してそのマシンの`nspawnmgr.env`に
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`を書き込みません — sudo/ホスト名の設定のみです
— そのため上記のcurlチェックは：

- **`200`** — まだ動作するデータベースがなく、§4の「初回起動セットアップウィザード」
  で説明されている初回起動セットアップウィザードを見ています。これは真新しい`.deb`
  インストール直後の通常の状態です；続行するにはウィザードに入力してください。
- **`302`**（`/nspawnmgr/`へのリダイレクト）— すでに動作するデータベースが設定
  されています。それをたどってください。実際のアプリが正常に起動した場合は
  （ログインページへの）別の`302`を、そうでない場合は`404`を期待してください：
  nspawnmgrのSpringコンテキストが起動に失敗しています。パッケージ自体が壊れている
  と決めつける前に、`sudo machinectl shell nspawnmgr journalctl -u tomcat9`を確認
  してください（nspawnmgr自身のWeb UIの「View log」ページはここでは役に立ちません
  — nspawnmgr自体が起動するところまでたどり着けなかったためです）；たいていは
  そのマシン自身の`/etc/nspawnmgr/nspawnmgr.env`の中の値が欠けているか間違って
  います（§9で各設定が何を意味するかをカバーしています）。

**これの後にまだ手作業で残っているもの**：初回起動ウィザード（§4）をMySQL/PostgreSQL
サーバーに向けること — これは`nspawnmgr`と`guacamole`両方のデータベースを作成し、
両方のアプリのスキーマを実行し、Guacamoleの`guacamole-auth-jdbc`拡張をあなたの代わりに
配線しますが、それでも一度は実行する必要があり、その後Guacamoleの管理者アカウントを
作成する必要があります；少なくとも1つのコンテナテンプレート（§2の「コンテナ
テンプレート」— 1つが存在するまで何も作成できません；真新しいインストールはゼロから
始まるため、`/admin/templates`のワンクリックの「Set up debian-minimal」ボタンは
すぐに利用可能です）；§9に照らして`/etc/nspawnmgr/nspawnmgr.env`の残りを見直す/調整
すること（Guacamoleのbase-urlなど — 生成されたファイルはsudoの資格情報、
`APP_SECRET_KEY`、そしてこのホスト自身のバンドルされた`auth.war`を指す
`USER_ID_URL`/`AUTH_LOGIN_URL`を埋めますが、意味のある自動生成されたデフォルトを
持たないアプリケーション設定は埋めません）、HTTPSの有効化（§6の「HTTPSを有効に
する」— `.deb`はデフォルトでTomcatをプレーンHTTPのままにします、手作業の経路と
同じです；管理者承認モードを使っている場合は、そのセクションのとおり強く推奨
されます）、そして検証（§10）です。

`postrm`は、パッケージの削除/パージ時に`nspawnmgr_exec`や`/etc/nspawnmgr`を意図的に
決して削除しません — そのアカウントは、あなたのコンテナが到達可能であり続ける唯一の
資格情報です。

**既存のインストールを新しいパッケージビルドにアップグレードする**（バグ修正であり、
新規インストールではありません）：
`sudo /usr/lib/nspawnmgr/upgrade-nspawnmgr.sh <path-to-the-new-package-file>`。
単なる`apt install`/`dnf install`/`pacman -U` — あるいは`apt install --reinstall`
でさえも — それだけでは不十分です：記録されているインストール済みバージョン文字列が
変わっていない場合、それらは黙って何もしない可能性があります。これは、開発サイクル内
のすべてのビルドが同じ固定バージョンの下で再公開されるため、重要です。このスクリプト
は代わりに、与えられたパッケージファイルを直接インストールします（記録されている
バージョンにかかわらず、常にその内容を適用します）。これは順番にパッケージ自身の
postinstallを再トリガーします — そしてそれは常に`nspawnmgr-bootstrap-app-machine.sh`
を呼び出し、これは初回インストール時だけでなく、呼び出されるたびに毎回セルフホスト型
`nspawnmgr`マシンの内容を完全に整合させます：4つのバンドルされたWAR、`guacd`自身の
バンドルとサービス、Tomcatのサービスユニット、そしてSSHで戻るための資格情報ファイルは
すべて更新され、まだ使用中のものが上書きされないよう、その周りでマシンが停止/再起動
されます。既存のホスト転送ポートはアップグレードをまたいで保持され、選び直されません。
非破壊的です — `/var/lib/machines`（他のすべてのコンテナ）と両方のデータベースは
完全にそのまま放置されます；ベースのrootfsクローンと、マシン内部の`tomcat`/`guacd`
システムアカウントも同様にそのまま放置されます（それらに再び触れると、本物の管理者
カスタマイズを壊してしまうか、2回目の実行で完全に失敗する可能性があります）—
Tomcatの*バージョン*アップだけは、以前と同様に依然として完全な再インストールが必要
です。

**とにかくすべてを削除する**（テストマシン、ゼロからやり直す — あなたのコンテナが
到達可能であり続けるためのsudo/SSHの資格情報を削除するため、十分に考えずに本番の
デプロイで実行するものではありません）：
`sudo /usr/lib/nspawnmgr/uninstall-nspawnmgr.sh`。`apt purge`がすでに行うことに加え、
これは`/opt/tomcat9`、`/etc/nspawnmgr`、`/etc/guacamole`、`/var/lib/nspawnmgr/templates`
（`TEMPLATES_DIR` — 「Set up debian-minimal」ボタンがダウンロードしたものを含む
テンプレートtarball；パージ後に残されたテンプレートファイルは、まさに後の再インストール
でそのボタンの「must not already exist」チェックを失敗させるものです）、
`tomcat`/`nspawnmgr_exec`システムアカウント、そしてnspawnmgrが設定した
[マシンの起動設定](#ホスト起動時の自動起動)（自動起動ユニットの有効化、別のマシンを
要求するドロップイン）も削除します — これは純粋にマシン名だけをキーとするsystemd
ユニットファイルの状態であり、`apt purge`によっても、コンテナ自体を削除することに
よってすら触れられません。以前のインストールから残った古い`Requires=`ドロップインは、
それだけで真新しい再インストールを完全に壊すのに十分です（`machinectl start
nspawnmgr`が「A dependency job for systemd-nspawn@nspawnmgr.service failed.」で
失敗します。要求していたユニットがもう存在しないためです）— ここにあるものはすべて、
その保守性が望ましくない場合のために、`postrm`が意図的に残しているものです。デフォルト
では、これは依然としてnspawnmgr自身のデータベース、Guacamole自身のデータベース、
または`/var/lib/machines`（あなたの実際のコンテナ）には**触れません** — それらを
取り巻く管理レイヤー（それらの作成に使われたテンプレートを含む）だけです —
ただし別途、`nspawnmgr`/`guacamoleデータベースとそのDBユーザーも削除するか
（`DB_URL`が`localhost`/`127.0.0.1`を指す場合のみサポートされます。これらの
ファイルが削除される前に`db.properties`/`nspawnmgr.env`から読み取られます）、
`machinectl`に現在登録されているすべてのコンテナを削除するかを尋ねます（それぞれ
独自のy/nプロンプトで、`--yes`によって暗黙には行われません）。実際のテストホストを
反復のたびに素早くリセットするのに便利です。この2つのステップは実際のデータ損失
であるためです。

### Arch Linuxへのインストール

ビルドとインストールはどちらも実際のArch系システムで実地に検証済みです：この正確な
`PKGBUILD`に対する`makepkg -f`（acer上の`arch-runner`のsystemd-nspawnコンテナ —
`packaging/ci/arch-runner/`参照）は、`.gitea/workflows/build.yml`の`arch-package`
ジョブ経由で本物の`nspawnmgr-0.3.0-1-any.pkg.tar.zst`を生成し、結果として得られる
パッケージ自身の`pacman -U` + `nspawnmgr.install`のフックは、実際のSteamOSシステム
（Archベース、`steamos-readonly disable`を実行すれば`pacman`互換）で繰り返し実行
されています — 新規インストール、アンインストール/再インストールのサイクル、そして
`upgrade-nspawnmgr.sh`経由のその場でのアップグレードが、セルフホスト型マシンが本物の
ネットワークリースとともに立ち上がり、Web UIが正しく応答することを含めて、すべて
動作確認済みです。SteamOSの小さなrootパーティションは`/home`配下にストレージを再配置
する必要があるため、**別個の**パッケージ`packaging/nspawnmgr-steamos/`が特にSteamOS
向けに存在します（これに対する自身の`provides`/`conflicts`参照 — 2つのうち正確に
1つだけをインストールしてください、両方は決してだめです）；非SteamOSのArchホストは
代わりにこのプレーンなArchパッケージをインストールすべきです。その非SteamOSの経路 —
（SteamOSと同じ基盤の`pacman`/`systemd`の仕組みを共有しますが同一ではない）SteamOS
ではなく、純粋なArchにこの正確なパッケージを直接インストールする — はまだ直接
テストされていません；試した場合は何が壊れたか報告してください。

`packaging/nspawnmgr-arch/`（`PKGBUILD` + `nspawnmgr.install`であり、Mavenモジュール
ではありません — Mavenネイティブなarchパッケージングプラグインは存在しません）は
それ以外の点では上記のオプションAとまったく同じセルフホスト型アーキテクチャです。
異なるパッケージ形式というだけです：同じ`nspawnmgr_exec`アカウント/sudoers/
ブリッジ/dnsmasqのセットアップ、同じセルフホスト型`nspawnmgr`マシン（このホスト
自身のディストリにかかわらず、依然としてDebian-minimalです — [§1](#1-アーキテクチャ概要)
参照 — Archホストはセルフホスト型の*アプリマシン*が何を実行するかを変えません、
*ベアホスト*自体が何を必要とするかだけを変えます）、そしてオプションAと同じ
「たった今、自動的に何が起きたか」、「正しく反映されたか確認する」、そして
「これの後にまだ手作業で残っているもの」— 上記を参照してください、ここでも変わらず
適用されます。違いは狭いものです：

- **依存関係**：`openssh`、`openssl`、`dnsmasq` — JREなし、`apache2-utils`相当品なし
  （どちらもセルフホスト型のアプリマシンの*内側*にインストールされ、ベアホストには
  まったく不要です — `nspawnmgr-bootstrap-app-machine.sh`参照）、
  `systemd-container`相当品なし（`machinectl`/`systemd-nspawn`はすでにArch自身のベース
  の`systemd`パッケージに含まれています）。
- **ファイアウォールのステップなし**：`.deb`の`ufw` DHCP例外設定とは異なり、Archは
  デフォルトでファイアウォールを何も有効化しないため、回避すべきものがありません。
  自分で`nftables`/`iptables`/`ufw`をセットアップしている場合は、`nspawnbr0`への
  インバウンドUDP/67が許可されていることを確認してください（`.deb`自身の`ufw`
  ステップが存在するのと同じ要件です）。
- **削除はデフォルトで保守的なままです**：`pacman -R`/`-Rns`は、`dpkg`/`aptが持つ
  ような、パージと削除の区別を提供しません。そのため`nspawnmgr.install`の
  `post_remove()`は`postrm`自身のデフォルト（非パージ）の振る舞いと同じくらい意図的に
  少ないことしか行いません — `.deb`と同じ`uninstall-nspawnmgr.sh`スクリプトが完全な
  クリーンアップを処理し、同じパスに引き続きインストールされています。

ビルドとインストール：

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_ARCH_PKG=1 tools/scripts/build-all.sh   # needs `makepkg` on PATH - a real Arch host, or the
                                               # archlinux/devtools container image

sudo pacman -U packaging/nspawnmgr-arch/nspawnmgr-0.4.0-1-any.pkg.tar.zst
```

### Fedora/RHEL（RPM）へのインストール

ビルドとインストールはどちらも、`Enforcing` SELinux下の実際のFedora 43ホストで実地に
検証済みです（ビルド用にはacer上の`fedora-runner`のsystemd-nspawnコンテナ —
`packaging/ci/fedora-runner/`参照 — と、インストール検証用の別個の`fedora-test-vm`
QEMUゲスト）：本物のエンドツーエンドフロー（DBセットアップウィザード、ログイン、
コンテナ作成、そして`upgrade-nspawnmgr.sh`経由の繰り返しのその場でのアップグレード）
は、特にSELinux Enforcing下でも含めて、動作確認済みです。

`packaging/nspawnmgr-rpm/`（本物のMavenモジュールです — `rpm-maven-plugin`は見た目
にかかわらず純粋なJavaではなく、実際に`rpmbuild`にシェルアウトします）は、それ以外の
点では上記のオプションAと同じセルフホスト型アーキテクチャです — 同じ`nspawnmgr_exec`
アカウント/sudoers/ブリッジ/dnsmasqのセットアップ、同じセルフホスト型`nspawnmgr`
マシン（このホスト自身のディストリにかかわらず、依然としてDebian-minimalです）、
オプションAと同じ「たった今、自動的に何が起きたか」、「正しく反映されたか確認する」、
そして「これの後にまだ手作業で残っているもの」。違いは狭いものです：

- **依存関係**：`openssh-server`、`openssl`、`dnsmasq`、`systemd-container`、そして
  `iptables-nft` — 実際に`/usr/bin/iptables`を提供する、Fedoraのnftablesベースの
  パッケージです（プレーンな`iptables`というパッケージ名はFedoraには存在しません；
  コンテナごとのアウトバウンドインターネットのトグルには、バックエンドにかかわらず
  本物の`iptables`バイナリが必要です）。
- **firewalldの例外設定**：Fedoraはデフォルトで`firewalld`をアクティブな状態で出荷
  します。インストールは`nspawnbr0`をfirewalldの`trusted`ゾーンに追加し、リロードし
  ます — これがないと、firewalldのデフォルトゾーンポリシーがコンテナへのDHCPリース
  を黙ってブロックしてしまいます。SteamOS自身の`firewalld`例外設定（下記）と同じ
  失敗の形です。
- **SELinuxポリシーモジュール**：`Enforcing`モード下では、`systemd_machined_t`は
  小さなカスタムポリシーモジュール（`nspawnmgr_machined_cgroup.te`、実行中の
  ポリシーバージョンに正確に一致するよう、インストール時に`checkmodule`/
  `semodule_package`/`semodule -i`からソースからコンパイルされます。事前コンパイル
  された`.pp`として出荷されるのではありません）を必要とし、`cgroup_t`ファイルに対する
  `watch`を付与します — これは、他の面ではストックのEnforcing Fedoraホスト全般に
  ある一般的なSELinuxポリシーのギャップであり、nspawnmgr固有のものではありません
  が、それがないとすべての`machinectl`/`systemd-nspawn`コンテナの起動が
  「Failed to register machine: Access denied.」で壊れます。
- **削除はデフォルトで保守的なままです**。他の2つのパッケージ形式と同じ姿勢、同じ
  `uninstall-nspawnmgr.sh`スクリプトです。

コードのバグではない、1つの環境トポロジーの注意点：`AUTH_LOGIN_URL`の自動検出された
ホスト名は、実際にブラウザが接続する場所から解決可能である必要があります（意図的な
設計上の選択です — [§9](#9-nspawnmgrの設定)参照 — これはより悪い、クッキースコープの
ログインループを回避します）。これは特に、直接到達可能な本物のホスト名ではなく、
NAT/トンネル/ポートフォワードのトポロジー越しにテストしている場合に問題になり得ます；
その場合は`AUTH_LOGIN_URL`を手で調整してください。

ビルドとインストール：

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_RPM=1 tools/scripts/build-all.sh   # needs a real `rpmbuild` binary (`rpm-build` package) -
                                          # a real Fedora/RHEL host, no cross-platform equivalent

sudo dnf install ./packaging/nspawnmgr-rpm/target/rpm/noarch/nspawnmgr-0.4.0-1.noarch.rpm
```

### オプションB：ソースからビルドして手動でデプロイ

**この経路は、あなたが作業しているホスト上に直接Tomcatをデプロイします — オプションA
のように、nspawnmgrを自分自身のマシンへセルフホストすることはしません。** それで
問題ありません；セルフホスティングは`.deb`のpostinstが行う意図的な選択であり、
厳格な要件ではありません — 手作業でビルドされた、ホストTomcatのデプロイは依然として
完全にサポートされており、単により古い/シンプルなトポロジーというだけです。`.deb`
なしでセルフホスト型モデルが欲しい場合、最も直接的な経路は、下記の§6に従うのでは
なく、`nspawnmgr-bootstrap-app-machine.sh`を読み通し、それが行うことを手作業で行う
ことです（テンプレートをベイクし、それを複製し、そのコンテナのrootfsにJRE/Tomcat/
WARをインストールするなど）。§6は以前とまったく同じように、ホスト自体にTomcatを
デプロイします。

リポジトリのルートから：

```bash
mvn -DskipTests package                # -> target/nspawnmgr.war
mvn -f auth/pom.xml -DskipTests package  # -> auth/target/auth.war
```

（`tools/scripts/build-all.sh`はどちらも行い、加えて開発専用のフェイクモジュールも
ビルドします — フェイクは本物のデプロイには不要です。）`.deb`があなたの代わりに
行っていた手作業のTomcat/アカウント/sudoersのセットアップについては§6に進んで
ください。

`.deb`のpostinstはさらに`/etc/nspawnmgr/auth-live/`（`tomcat:tomcat`所有、モード`750`）
も作成します — `/admin/settings`がauth.warのライブ設定を書き込む共有ファイルです
（[§9](#9-nspawnmgrの設定)参照）。手動デプロイでも、Tomcatの`tomcat`ユーザーが存在する
ようになったら（§6）、同じものが必要です：

```bash
sudo mkdir -p /etc/nspawnmgr/auth-live
sudo chown tomcat:tomcat /etc/nspawnmgr/auth-live
sudo chmod 750 /etc/nspawnmgr/auth-live
```

## 6. Tomcat 9（nspawnmgr + Guacamole + auth）

**このセクションでは、ホストに直接Tomcatをデプロイする方法を説明します** — 手動
（§5オプションB）インストールが取る形です。`.deb`/Arch/RPMパッケージ（§5オプションA）
経由でインストールした場合、Tomcatはそもそもホスト上にはありません — セルフホスト型
`nspawnmgr`マシンの内側にあり、すでに`nspawnmgr-bootstrap-app-machine.sh`によって
セットアップ済みです。このセクションはまったく適用されません；まっすぐ§7に進んで
ください。

Guacamoleの公式webappは今も`javax.servlet`を対象としているため、それとnspawnmgrは
**同じTomcat 9**インスタンスに並べてデプロイされます。

**aptの依存関係ではありません。**`guacd`（§7）と同様、apt自身の`tomcat9`パッケージの
可用性はDebian/Ubuntu/Mintのリリースによって十分に変わるため、このプロジェクトはそれに
頼るのではなく、バニラのアップストリームのApache Tomcatバイナリ配布を代わりにバンドル
します — apt archiveがたまたま持っている何かではなく、現行のパッチリリース
（9.0.120）で、このパッケージはインスタンス全体を自分自身で所有します
（`/opt/tomcat9`、独自の`tomcat`システムユーザー、独自の`tomcat9.service`）。
**このパッケージの以前のバージョン（apt `tomcat9`に依存していたもの）がすでに
インストールされている場合は、まずそのパッケージの`tomcat9`を削除してください** —
2つのTomcatインスタンスがどちらも`:8080`にバインドしようとすると失敗します。

そうでなければ（オプションB）、自分で新しいコピーをダウンロードするのではなく、
`.deb`が出荷するのと同じバンドルされたtarball —
リポジトリのチェックアウトでは`packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz`
— を展開してください。そうすれば手動インストールが、このプロジェクトがテストされて
いる正確なパッチリリースと一致します：

```bash
sudo mkdir -p /opt/tomcat9
sudo tar -xzf packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz -C /opt/tomcat9 --strip-components=1
sudo chmod +x /opt/tomcat9/bin/*.sh
```

Tomcatを、それ自身の非特権の、sudoなしのシステムユーザーとして実行してください
（決してrootではなく、意図的に[§3](#3-sudo権限を持つsshアカウント)と同じアカウント
でもありません）：

```bash
sudo useradd -r -M -d /opt/tomcat9 -s /usr/sbin/nologin tomcat
sudo chown -R tomcat:tomcat /opt/tomcat9
```

**これより前に[§3](#3-sudo権限を持つsshアカウント)を行った場合**（文書化されている
順序です）、戻って、それが生成したSSHキーペア（`SSH_PRIVATE_KEY_PATH`、デフォルトは
`/etc/nspawnmgr/ssh_id_ed25519`）を、今存在するようになったこの`tomcat`ユーザーが
読めるようにしてください — `SshRemoteExecutor`は、権限を要する操作のたびにTomcat自身
のプロセスの内側から直接そのファイルを開きます。そしてその鍵は、その時点ではまだ
`tomcat`が存在しないため、`root:root`モード`600`（グループアクセスなし）で作成
されます：

```bash
sudo chown root:tomcat /etc/nspawnmgr/ssh_id_ed25519
sudo chmod 640 /etc/nspawnmgr/ssh_id_ed25519
```

これをスキップすると、すべての権限を要する操作が「Failed to establish SSH connection
to 127.0.0.1:22」で失敗し続けます — この文言にもかかわらず、接続の問題ではなく権限の
問題です。

アップストリームのtarballには、Debian自身の`tomcat9`パッケージが別個の、デフォルトでは
インストールされないサブパッケージに分割している`manager`/`host-manager`/`examples`/
`docs`のwebappがバンドルされています；`.deb`のpostinstは初回インストール時にこれらを
取り除きます。理由は同じです — 設定されないままデプロイされたままだと、実際に、
回避可能な攻撃対象領域になります — ここでも手作業で行う価値があります：

```bash
sudo rm -rf /opt/tomcat9/webapps/manager /opt/tomcat9/webapps/host-manager \
       /opt/tomcat9/webapps/examples /opt/tomcat9/webapps/docs
```

nspawnmgrをデプロイします：

```bash
sudo cp target/nspawnmgr.war /opt/tomcat9/webapps/nspawnmgr.war
```

nspawnmgr、Guacamole、そして`auth`（§8）はそれぞれ下記で独自のコンテキストパスを
取ります — そのパスを手放さない限り、どれもサーバーのrootを主張できません — そのため
裸の`http://<hostname>:8080/`向けに、このリポジトリ自身の`site/root-index/index.html`
を参考として使った小さな静的リダイレクトページを配置してください（`/nspawnmgr/`へ
リダイレクトします）：

```bash
sudo mkdir -p /opt/tomcat9/webapps/ROOT
sudo cp site/root-index/index.html /opt/tomcat9/webapps/ROOT/index.html
sudo chown -R tomcat:tomcat /opt/tomcat9/webapps/ROOT
```

Tomcatの起動をラップする何か（systemdユニットの`Environment=`/`EnvironmentFile=`、
または`CATALINA_OPTS`下の`bin/setenv.sh` — `catalina.sh`は`$CATALINA_OPTS`をシェル
コマンドラインとして再評価するため、`;`を含む場合はすべての`-D`値を引用符で囲んで
ください。エスケープされていない`;`はコマンドセパレータとしてパースされ、起動を
黙って切り詰めてしまいます）の中に`SPRING_PROFILES_ACTIVE=prod`（加えて
[§9](#9-nspawnmgrの設定)の他のすべての環境変数）を設定してください。プロファイルが
アクティブでない場合、nspawnmgrはデフォルトで`dev`になります（インメモリH2、フェイクの
エグゼキューター）— これはここで望むものではありません。

再起動を生き延びるよう、これをsystemdサービスとしてセットアップしてください。例えば
`/etc/systemd/system/tomcat9.service`（`.deb`がインストールするのと同じユニットです
— リポジトリのチェックアウトの`packaging/nspawnmgr-deb/tomcat9.service`が既製の参考
例です）：

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

`startup.sh`/`shutdown.sh`を伴う`Type=forking`ではなく、`catalina.sh run`
（フォアグラウンド）を伴う`Type=simple`です — この方法だとsystemdはJVMを直接監視する
ため、クラッシュが検出され`Restart=on-failure`が実際に発動します；forkingユニットは
*ラッパースクリプト*自体が終了したかどうかしか知らず、Tomcat自体がまだ生きているか
どうかは分かりません。

```bash
sudo systemctl enable --now tomcat9
```

### 別のポートを使う

Tomcatはデフォルトで`8080`をリッスンします（`conf/server.xml`の
`<Connector port="8080" .../>`）。これを変更するには、その`port`属性を直接編集して
ください：

```bash
sudo sed -i 's/port="8080"/port="8180"/' /opt/tomcat9/conf/server.xml
```

または`server.xml`を手編集する代わりに、`/admin/settings`の**Tomcat**セクションを
使ってください — これは同じファイルを読み書きします（Tomcat自身の起動スクリプトが
常に設定する`catalina.base` JVMシステムプロパティ経由で位置を特定するため、`.deb`の
Debianパッケージ化された`tomcat9`を実行していても、`/opt/tomcat9`配下の手動で展開
されたものを実行していても、正しい`server.xml`を見つけます）。他のすべての権限を
要する操作がすでに使っているのと同じsudo権限を持つSSHアカウントと
`nspawnmgr-write-file.sh`ラッパースクリプトを経由します — 新しいsudoersの許可は
不要です。**権威を持つのはファイル自体**であり、データベースのコピーではありません：
このページは常に実際にディスク上にあるものを表示・編集するため、（上記のような）
`server.xml`の直接の手編集と、設定ページを使うことは完全に互換性があります —
どちらも互いに対して古くなることはありません。

このガイド内の（そしてあなた自身の設定内の — `nspawnmgr.auth.user-id-url`/
`AUTH_LOGIN_URL`、`nspawnmgr.guacamole.base-url`、そしてユーザーに訪問するよう伝える
URL）他のすべての`:8080`は、これに一致するよう更新される必要があります — どちらの
方法で変更しても、`server.xml`から自動的にポートを導出するものは何もありません。
`/admin/settings`では、これはほとんどフィールドごとのワンクリックです：それらの
URLフィールドそれぞれに、Tomcatセクションの現在のポート/HTTPS状態に加え
`host.external-hostname`（§8）から書き換える「Refresh hostname/port/protocol」
ボタンがあります — それぞれのURLのポートを個別に手編集する必要はありません。
ファイアウォールの内側にいる場合は、`8080`の代わりに新しいポートが開いていることを
確認してください。どちらにしても、この変更は再起動後にのみ有効になります —
`/admin/settings`のRestart Tomcatボタン（上記参照）または
`sudo systemctl restart tomcat9`を自分で使ってください。

### HTTPSを有効にする

実際のデプロイがこれを実際にどう行っているかの順で、2つの選択肢があります：

1. **リバースプロキシ（nginx、Apache、Caddy、クラウドのロードバランサー）でTLSを
   終端する** — Tomcatの前段に置き、Tomcat自体はプレーンなHTTPで
   `127.0.0.1:8080`のみをリッスンし続けます（`server.xml`の
   `<Connector address="127.0.0.1" .../>`でループバックにバインドし、直接到達
   できないようにしてください）。これは通常、証明書の更新（例：Certbot/
   Let's Encrypt）にとってより簡単な経路です。Tomcat自身のキーストア形式から
   切り離されているためです。このガイド内のすべての`nspawnmgr.*`/
   `AUTH_LOGIN_URL`のURLを、`http://<hostname>:8080/...`の代わりに
   `https://<hostname>/...`（プロキシがリッスンしているポート）に向けてください
   — [§8](#ホスト名と共有セッションクッキー)のホスト名/クッキーの要件が実際に
   適用されるのは、Tomcatではなくプロキシに対してです。

2. **リバースプロキシを実行したくない場合は、代わりに直接TomcatのSSLコネクタを
   設定する。** Tomcat 8.5/9以降、`<SSLHostConfig>`の`<Certificate>`要素は
   PEM証明書/鍵を直接受け付けます（`certificateFile`/`certificateKeyFile`/
   `certificateChainFile`）— Javaキーストアへの変換は不要です。これは重要です。
   これはまさに、Let's Encrypt/ACMEクライアント（例：Certbot）があなたに渡す形式
   だからです（`fullchain.pem`/`privkey.pem`）。このホストにCertbotを向けて
   （`certbot certonly --standalone -d nspawnmgr.example.com`、またはあなたの
   セットアップに合った任意のプラグイン）、`server.xml`にコネクタを追加して
   ください：

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

   `tomcat`システムユーザーは`/etc/letsencrypt/live/.../*.pem`への読み取りアクセス
   を必要とします（Let's Encrypt自身のディレクトリは通常デフォルトでroot専用です
   — その2つのファイルだけ権限を緩めるか、Tomcatが読める場所にコピーし、更新の
   たびに再コピーしてください）。Tomcatを再起動し、その後このガイドのいたる
   ところで`http://<hostname>:8080/...`の代わりに`https://<hostname>:8443/...`
   を使ってください。プレーンHTTPのコネクタを完全に削除するか、その
   `redirectPort="8443"`を設定して、はぐれたHTTPリクエストが平文で提供される
   のではなくHTTPSへ跳ね返されるようにしてください。Certbotの更新はあなたの
   代わりにTomcatを再起動しないため、更新された証明書が実際に反映されるよう
   `--deploy-hook "systemctl restart tomcat9"`（または`renewal-hooks/deploy/`
   スクリプト）を追加してください。

   `/admin/settings`の**Tomcat**セクションは、まさにこのコネクタブロックを
   あなたの代わりに構築/編集します — 「HTTPS」ドロップダウンと2つのPEMパスです
   — 「別のポートを使う」で説明したのと同じ、ファイルが権威を持つ、SSHラッパー
   スクリプトの仕組みを使います。これはあなたの代わりにプレーンHTTPコネクタを
   削除したり`redirectPort`を設定したりすることは決してなく、保存時には既存の
   `<Certificate>`要素のパスを常に完全に置き換えます。マージはしません — ここに
   示されている以上にコネクタをカスタマイズしている場合（`RSA`でない証明書
   タイプ、複数の`SSLHostConfig`エントリなど）は、代わりに`server.xml`を手で
   編集してください。

どちらのオプションを選んでも、このガイドの他の場所で参照されているすべての
`http://`のURL — ブラウザが見るものだけでなく`application.yml`/環境変数の内側の
ものも含めて — は、一致するよう`https://`にならなければなりません；nspawnmgrに
設定されているものと実際に提供されているものの不一致は、リダイレクトループや
クッキーが送られない失敗のよくある原因です。

**管理者承認モードを使っている場合**（[§3](#3-sudo権限を持つsshアカウント)）、
他に何もそうするよう促していなくても、ここでHTTPSを有効にすることが強く推奨
されます：承認ページは管理者のsudoパスワードを平文のフォームフィールドとして
送信します。これは、平文HTTPを介した場合、nspawnmgrが提供する他の何よりも
意味のある大きな露出です。文書化されているデフォルトのインストールはHTTPの
ままです — これはそのモードに特有の推奨事項であり、デフォルトの変更ではありません。

## 7. Guacamole

**Guacamoleの3つのコンポーネントのうち、現行のどのDebian/Ubuntu/Mintリリースにも
aptパッケージは存在しません**：`guacd`と`guacamole-tomcat`はbookworm、trixie、jammy、
nobleでゼロ件を返し、Debian unstableでさえ`guacd`を`ia64`/`riscv64`向けにしかビルド
していません。`amd64`向けではありません。それぞれ異なる方法で扱われており、どれか
1つだけでは動作するセットアップにはなりません：

| コンポーネント | パッケージ化されている？ | それが行うこと |
|---|---|---|
| `guacd` | **いいえ。** `.deb`は代わりに自己完結型のビルド（独自のOpenSSL 3.x、最小限のFFmpeg、FreeRDP2、libssh2 — 正確な理由と方法については`/usr/share/doc/nspawnmgr/guacd-bundle-README.md`参照）をバンドルし、それを独自の`guacd.service`systemdユニットとして実行します — どのインストールオプションでも、システムパッケージも手作業のステップも不要です。 | ネイティブのプロキシデーモン |
| `guacamole-tomcat` | **いいえ。** これもバンドルされていません（これは通常あなたの代わりに`guacamole.war`をデプロイする*パッケージングの糊*です）— しかし`guacamole.war`自体はバンドルされています：`.deb`はそれをバンドルされたTomcatに直接デプロイします、`nspawnmgr.war`/`auth.war`と同じです（下記参照）。 | `guacamole.war`をTomcatに自動的にデプロイする |
| `guacamole-auth-jdbc` | **いいえ。** aptパッケージではありませんが、`guacd`と同じ方法でバンドルされています — 一度ダウンロードされ、チェックサム検証され、`packaging/nspawnmgr-deb/vendor/`にコミットされたtarballです（`vendor/README.md`参照）。インストール時に新しくフェッチされるのではありません。`.deb`のpostinstはこれを自動的に展開します、ネットワーク不要です；手動インストールでは、下記のとおり手作業で同じスクリプトを実行します。**任意ではなく必須です** — 下記参照。 | GuacamoleにMySQL/PostgreSQLの接続ストレージバックエンドを提供するJDBC拡張、そしてそのSQLスキーマスクリプト |

`guacamole-auth-jdbc`は、代わりに選べる複数のバックエンドの中の1つの選択肢ではありません
— nspawnmgrは、Guacamoleのすべての接続とユーザーをGuacamoleのREST API経由で管理して
おり（下記の「GUACAMOLE_HOMEと認証バックエンド」参照）、そのAPIはGuacamoleがデータベース
バックエンドの認証拡張を実行しているときにのみ存在します。Guacamole自身のデフォルト
（`user-mapping.xml`、APIを持たない静的なXMLファイル）はそれを公開しません。この
ステップをスキップしても、機能が縮小した動作するnspawnmgrにはなりません — 代わりに
得られるのは、コンテナ接続をまったく作成も管理もできないnspawnmgrです。「このユーザーに
このコンテナへのアクセスを与える」というアクションはすべて、最終的にこのAPIを呼び出す
ためです。`.deb`の自動化があっても、tarballを展開するだけでは下記の§7ステップ1が説明
する半分でしかありません — JAR/ドライバはそれでも手作業で`GUACAMOLE_HOME`にコピーする
必要があり、`guacd`と`guacamole.war`のどちらがデプロイされていても、これのいずれかが
行われていることを意味しません；別途確認してください。

### guacd

`.deb`（§5オプションA）経由でインストールした場合、これはすでに完了しています —
`nspawnmgr-bootstrap-app-machine.sh`が自己完結型のバンドルを`/opt/guacd-bundle`に
展開し、ホスト上ではなく**セルフホスト型`nspawnmgr`マシンの内側で**`guacd.service`
を起動しました（`sudo machinectl shell nspawnmgr systemctl status guacd`で確認できます）
— 下記の「guacamole.war」に進んでください。

そうでなければ（オプションB、ホストTomcatのデプロイ —
[§6](#6-tomcat-9nspawnmgr--guacamole--auth)）、どこかから本物の`guacd`バイナリが
必要です。aptはどの現行リリースでも提供しないためです。最も直接的な経路は、`.deb`が
出荷するのと同じ自己完結型ビルドを再利用することです：リポジトリのチェックアウトの
`packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz`（または
`packaging/nspawnmgr-deb/vendor/README.md`のレシピに従って自分のコピーをビルドして
ください — これはすべてのステップを文書化しており、実際に発見に時間がかかった2つの
本物の落とし穴を含みます：CMakeが再構成をまたいで古いOpenSSLパスを黙ってキャッシュ
すること、そして`-Wl,-rpath`だけでは、一致する`-L`なしでは不十分であることです）。
これを展開し、`postinst`と同じ方法でsystemdユニットをインストールしてください：

```bash
sudo tar -xzf packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz -C /opt
sudo adduser --system --home /nonexistent --no-create-home --group guacd
sudo cp packaging/nspawnmgr-deb/guacd.service /etc/systemd/system/guacd.service
sudo systemctl daemon-reload
sudo systemctl enable --now guacd
```

### guacamole.war

`.deb`（§5オプションA）経由でインストールした場合、これもすでに完了しています —
`nspawnmgr-bootstrap-app-machine.sh`は、**セルフホスト型`nspawnmgr`マシンの内側**の
`/usr/share/nspawnmgr/guacamole.war`を指すコンテキスト記述子経由で、
`packaging/nspawnmgr-deb/vendor/guacamole-1.5.5.war`（同じ公式Apacheリリースで、
一度ダウンロードされチェックサム検証済み、インストール時に新しくフェッチされるもの
ではありません）を、`nspawnmgr.war`/`auth.war`と並んでデプロイしました。
`curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<forwarded port>/guacamole/`
で確認してください（`200`、またはGuacamole自身のログインフローへのリダイレクトを
期待してください）。そして下記の「GUACAMOLE_HOMEと認証バックエンド」に進んでください。

そうでなければ（オプションB、ホストTomcatのデプロイ）、同じファイルを自分でダウンロード
してデプロイしてください：

```bash
GUACAMOLE_VERSION=1.5.5
curl -fsSL -o guacamole.war \
  "https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/guacamole-${GUACAMOLE_VERSION}.war"
sudo cp guacamole.war /opt/tomcat9/webapps/guacamole.war
```

### GUACAMOLE_HOMEと認証バックエンド

Guacamoleは、**接続ストレージバックエンド**用に`guacamole.properties`と
`guacamole-auth-jdbc`拡張のJAR/JDBCドライバを含む、独自の`GUACAMOLE_HOME`
（一般的には`/etc/guacamole`）を必要とします — これはnspawnmgr自身のデータベースとは
別個の関心事です。**§4の初回起動データベースウィザードは、今では下記のステップ1〜2を
自動的に行います**（正しい拡張JARをコピーし、`<vendor>-*`プロパティを書き込み、
スキーマを実行します）これは`guacamole`データベースをセットアップする一環としてです —
下記の手順は代わりに手作業でこれを行うためのものです（ウィザードにアクセスできない、
自動配線が失敗して警告が残った、または事後にデータベースを変更している場合）。`.deb`
経由でインストールした場合、このディレクトリと最小限の`guacamole.properties`
（同じインストールがすでに開始した`guacd`インスタンスを指す、単なる
`guacd-hostname`/`guacd-port`だけです）はすでに存在し、`tomcat:tomcat`所有です —
初回インストール時にのみ一度作成されるため、後の編集（手作業または
`/admin/settings`のGuacamoleエディター経由）はアップグレードを常に生き延びます。
そうでなければ（オプションB）、自分で作成してください：
`sudo mkdir -p /etc/guacamole && sudo chown tomcat:tomcat /etc/guacamole`。上記で
説明したとおり、JDBC認証拡張自体は必須であり、代替の中からの選択肢ではありません：
nspawnmgrは管理者アカウント（`nspawnmgr.guacamole.admin-username`/`admin-password`）
を使ってGuacamoleのREST API経由で接続/ユーザーを管理しており、`guacamole-auth-jdbc`
だけがそのAPIを公開します。そのため：

1. `guacamole-auth-jdbc`のtarballを展開させてください — 上記の`guacd`/
   `guacamole-tomcat`とは異なり、どのリリースにもこれに対するaptパッケージは
   ありませんが、`guacd`と同様に、インストール時にダウンロードされるのではなく
   直接バンドルされています：リポジトリのチェックアウトの
   `packaging/nspawnmgr-deb/vendor/guacamole-auth-jdbc-1.5.5.tar.gz`は、`.deb`が
   出荷するのと同じtarballで、すでに一度ダウンロードされApache自身の`.sha256`に
   対してチェックサム検証済みです。`install-guacamole-auth-jdbc.sh`はこれを
   （ネットワーク不要で）固定の、バージョンに依存しない**決め打ちのインストール
   場所**、`/etc/guacamole/guacamole-auth-jdbc/`（`mysql/schema/`と
   `postgresql/schema/`のサブフォルダ、最終的にどちらのデータベースを使うかに
   かかわらず — tarballは両方を出荷します）に展開します。これはGuacamole自体が
   要求するパスではなく、単にnspawnmgr自身の規約です：
   - **`.deb`インストール**：これは`postinst`の一部としてすでに自動的に実行
     されています — もし失敗した場合は（例えばtarballが何らかの理由で
     `/usr/share/nspawnmgr/`から欠けている場合）、
     `sudo /usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh`を手作業で再実行
     してください。
   - **手動インストール**、またはこれをやり直す場合（例えばGuacamoleのバージョンを
     上げるため — 最初にtarballを再ベンダリングしてください）：リポジトリの
     チェックアウトから`sudo
     packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh`を実行して
     ください（`--source-tarball`/`--target-dir`/`--force`フラグが利用可能です
     — スクリプト自身のヘッダーコメント参照）。

   どちらにしても、`/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/`から、
   選択したデータベース（`nspawnmgr.guacamole.data-source`、例えば`mysql`）向けの
   拡張JARを`GUACAMOLE_HOME/extensions/`にコピーしてください — これは依然として
   手作業のステップです。どのデータベースかという選択に依存しており、それは
   あなたに代わって誰にもできないためです。

   JDBCドライバ自体（上記の拡張JARとは別の、実際の`java.sql.Driver` —
   `guacamole-auth-jdbc`はこれを決してバンドルしません）は別の話です：
   nspawnmgr.warはすでに、自身の無関係なデータベース利用のために、MySQLと
   PostgreSQLの両方のドライバをバンドルしているため（ルートの`pom.xml`）、
   2つ目の別個のダウンロードの代わりに、`install-guacamole-jdbc-drivers.sh`は
   単にnspawnmgr自身のすでにビルド済みのドライバjarの両方を
   `GUACAMOLE_HOME/lib/`にコピーします — ネットワークアクセスはまったく不要で、
   実際には片方しか使われなくても両方がそこにあることに害はありません。上記の
   スキーマtarballと同様、これはすでに`.deb` postinstの一部として自動的に実行
   されています（ベストエフォートです — 何らかの理由で失敗した場合は
   `sudo /usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh`を再実行して
   ください）；手動インストールでは、`mvn -DskipTests package`の後、リポジトリの
   チェックアウトから
   `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh --source-dir target/guacamole-jdbc-drivers`
   を実行してください。
2. Guacamoleが所有するデータベースに対して、その拡張のスキーマスクリプトを実行して
   ください（これはnspawnmgr自身のデータベースと**同じではありません** — Guacamoleは
   独自のusers/connectionsスキーマを必要とします）。`/admin/settings`の
   Guacamoleセクションには**「Test database connection」**ボタンがあり、これが
   あなたの代わりにこれを行います：現在Databaseフィールドに入力されている内容で
   接続し、スキーマがセットアップされているように見えるかチェックし
   （`guacamole_connection`テーブルを探ります）、そうでなければ、あなたが指定
   したディレクトリ内のすべての`.sql`ファイルを実行することを申し出ます —
   「Schema scripts directory」フィールドは、すでにデフォルトで
   `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/schema`
   （その上のデータベースタイプ選択に一致します）になっているため、ステップ1が
   決め打ちの場所を使っていれば、これは通常編集不要の「Test」クリックです。
3. nspawnmgrが使う管理者アカウントを作成してください（`guacadmin`/`guacadmin`は
   JDBC拡張が初回実行時に出荷する、よく知られたデフォルトです — 実際のデプロイ
   では、すぐにパスワードを変更し、それに一致するよう
   `nspawnmgr.guacamole.admin-password`を更新してください）。
4. `guacamole.properties`で`guacd-hostname`/`guacd-port`を設定してください
   （デフォルトは`localhost:4822`。guacdが同じホスト上で動いていれば問題
   ありません）。

`GUACAMOLE_HOME`にファイルを配置した後はTomcatを再起動してください —
Guacamoleは拡張をホットリロードしません。

nspawnmgrをそれが立ち上がったらそこへ向けてください（`nspawnmgr.guacamole.base-url`）、
例えば`http://your-hostname:8080/guacamole`です。非デフォルトのパスを使った場合は
`nspawnmgr.guacamole.home`（`GUACAMOLE_HOME`、デフォルトは`/etc/guacamole`）も設定
してください — これは`/admin/settings`のGuacamoleエディターが`guacamole.properties`
を読み書きする場所です（[§9](#9-nspawnmgrの設定)参照）。追加の権限セットアップは
不要です：nspawnmgrとGuacamoleはどちらも、同じTomcatインスタンスの中で同じ`tomcat`
ユーザーとして実行され、`GUACAMOLE_HOME`はすでにGuacamole自身の利用のために
`tomcat`所有になっています。

## 8. `auth`（ログインバックエンド）

`auth.war`は、実際にユーザー名/パスワードをあなたのOSアカウント（PAM）またはSMB経由の
Windowsマシンに対してチェックし、nspawnmgrが信頼する共有セッションクッキーを発行する
ものです。これは`javax.servlet`（Servlet 4.0）を対象としており、nspawnmgrとGuacamoleの
webappと同じです。そのため**同じTomcat 9インスタンス**（§6）にデプロイされます —
別個のサーブレットコンテナは不要です。（ローカルでの素早い反復作業専用に、これは
`mvn -f auth/pom.xml jetty:run`経由でスタンドアロンでも実行でき、WARの再ビルド/再デプロイ
サイクルなしにポート9092でJetty上で起動します — 実際のデプロイに使うものではありません。）

これらは`auth/src/main/webapp/WEB-INF/web.xml`のcontext-params（編集後はWARを再ビルド
してください）、またはそのファイルに文書化されている、対応するシステムプロパティ
（`-D...`）経由で設定してください：

| 設定 | システムプロパティ | 目的 |
|---|---|---|
| `auth.backend` | `AUTH_BACKEND` | `pam`（デフォルト、authが動いているホスト自身のローカルLinuxアカウント）または`smb`（リモートWindowsマシン） |
| `smb.server` | `SMB_SERVER` | `auth.backend=smb`の場合は必須 — 認証対象のWindowsホスト |
| `smb.domain` | `SMB_DOMAIN` | 任意のNTLMドメイン |
| `auth.required-group` | `AUTH_REQUIRED_GROUP` | 任意、`pam`のみ — Unixグループ；認証されたユーザーがそのメンバーでない場合、ログインは拒否されます |
| `smb.required-share` | `SMB_REQUIRED_SHARE` | 任意、`smb`のみ — `smb.server`上のSMB共有；ユーザーがそれへのアクセス権を持っていない限り、ログインは拒否されます（これがグループチェックではなく共有チェックである理由については下記参照） |
| `cookie.name` | — | nspawnmgrの`nspawnmgr.auth.cookie-name`（デフォルト`nspawnmgr_session`）と一致していなければなりません |

**`smb`がグループメンバーシップではなく共有アクセスをゲートとする理由：** Windowsは
デフォルトで*リモートの*SAM/グループクエリを`BUILTIN\Administrators`に制限しています
（`RestrictRemoteSAM`）— これは、レジストリの調整にかかわらず、設計上、通常のユーザーが
グループチェックを通過することを永遠にできなくします。共有アクセスは、そのような制限
のない通常の、ACLでゲートされたSMB操作です。そのため、ログインを許可/拒否すべき
ユーザーに対して、`smb.required-share`に通常の共有とNTFS権限を設定することで、
アクセスを付与/拒否してください。

**`pam`にはTomcatアカウントの`/etc/shadow`への読み取りアクセスが必要です。** PAM経由の
パスワード検証は、最終的にはターゲットユーザーのハッシュを`/etc/shadow`（モード
`640`、`root:shadow`）から読み取ることを意味します — 通常は、呼び出し元プロセス自身の
グループにかかわらず、`pam_unix`自身のsetgid-`shadow`な`unix_chkpwd`ヘルパーを通じて
透過的に処理されますが、そのフォールバックはすべてのホストで信頼できるわけではありません
（ある実際のインストールでは、まさにこれに遭遇しました：`unix_chkpwd`のsetgid昇格が、
*どの*非root呼び出し元に対しても黙って効かなくなり、すべてのPAMログインが、
`auth.war`自身のログに実用的なエラーもなく、単なる「Login failed」で失敗しました）。
`.deb`のpostinstは、これを回避するために`tomcat`を`shadow`グループに直接追加します
（`usermod -aG shadow tomcat`）— これで`pam_unix`は`/etc/shadow`自体を読めるように
なり、どちらにせよ`unix_chkpwd`フォールバックは不要になります。手動（非`.deb`）
インストールにも同じものが必要です：`sudo usermod -aG shadow tomcat`、その後
Tomcatを再起動してください（グループメンバーシップは、変更*後に*起動されたプロセス
にのみ適用され、すでに稼働中のものには適用されません）。それでもPAMログインが失敗する
場合は、`/var/log/auth.log`で実際の`pam_unix(login:auth)`の行を確認してください —
PAM自体が実際に何を拒否したのかを見る最も直接的な方法です。`auth.war`自身の
「Login failed」ページは意図的に一般的なものだからです（資格情報の列挙を助ける
ヒントはありません）。

これを、nspawnmgr/Guacamole（`/nspawnmgr`と`/guacamole`を取ります）と同じTomcat 9
インスタンスの、独自の`/auth`コンテキストパスにデプロイして、`/auth/login`、
`/auth/userinfo`、`/auth/logout`を提供させてください（下記の`nspawnmgr.auth.user-id-url`
と一致します）：

```bash
sudo cp auth/target/auth.war /opt/tomcat9/webapps/auth.war
```

`tools/scripts/setup-auth-tomcat.sh`は、ローカルテスト向けに調整された、まさにこれの
参考例です。`auth`自身のログイン/ログアウトページは、ハードコードされたパスではなく
`request.getContextPath()`から内部リンク（例えば「Try again」）を構築するため、
ここで`/auth`にデプロイされていても、（ローカルの反復作業のために`jetty:run`経由で
などで）サーバーのrootにデプロイされていても、正しく解決されます。

### ホスト名と共有セッションクッキー

nspawnmgr、`auth`、そしてGuacamoleは**すべて同じホスト名を通じて到達可能でなければ
なりません** — `auth`が設定するセッションクッキーは、両方が同じオリジンのクッキー
スコープにある場合にのみnspawnmgrにとって有用です。3つすべてが今は1つのTomcat
インスタンスを共有しているため、これはほぼ自動的です（同じホスト、同じポート）が、
それでも本物のホスト名を選び（`localhost`は、すべてが本当に1つのボックス上にあり、
`localhost`としてしかアクセスしない場合を除きます）、DNSまたは`/etc/hosts`でホストの
IPを指すようにし、それを一度、**`nspawnmgr.host.external-hostname`**
（`HOST_EXTERNAL_HOSTNAME` — `/admin/settings`でライブ編集可能、Host配下の
「External hostname」；`.deb`インストールでは`setup-sudo-account.sh`によって
このマシンの実際のホスト名に自動的にシードされます、§5参照）に設定してください。
これは、そのページのすぐ下にある`nspawnmgr.host.public-address`と*同じ*設定では
ありません — 違いについては、そのフィールド自身の説明、または
[§9](#9-nspawnmgrの設定)を参照してください。

このホスト名が現れる必要のある他のすべての場所は、自動的には導出されない単なるURL
フィールドです — `nspawnmgr.auth.user-id-url`
（`http://<hostname>:8080/auth/userinfo`）、`nspawnmgr.guacamole.base-url`、
そして管理者/ユーザーに訪問するよう伝えるログインページ
（`http://<hostname>:8080/auth/login?returnTo=...`）— しかし`/admin/settings`が
そのギャップを埋めます：それらのURLフィールドそれぞれに**「Refresh
hostname/port/protocol」**ボタンがあり、上記のExternal hostnameに加えTomcatセクション
の現在のポート/HTTPS状態（§6参照）からそれを書き換えます。そのため、ホスト名または
ポートの変更は、残りをクリックして回る前に1か所に入力するだけで済みます。

これの手前でHTTPSを終端している場合、証明書のCN/SANはそのホスト名に一致していなければ
なりません — ここでの不一致は、「ログインは成功するがnspawnmgrはまだlogin-required
ページを表示する」の最もよくある原因です。

**常に`HOST_EXTERNAL_HOSTNAME`/`AUTH_LOGIN_URL`と同じホスト名でnspawnmgrにブラウズ
してください — たとえ同じボックスに解決されるとしても、`localhost`、IPアドレス、
または他のいかなるエイリアスでもありません。** `auth.war`が発行するクッキーには
`Domain`属性がないため、ログインページを提供したまさにそのhost:portにスコープが
限定されます — あなたが元々入力したホスト名ではなく、`AUTH_LOGIN_URL`が指している
ものです。nspawnmgrのlogin-required-toへのリダイレクトも、常にその同じhost:port
へ`returnTo`を送り返します（あなたが最初にどのホスト名で始めたかにかかわらず）。
そのため、ここでの不一致が永遠にループすることはありませんが、入力したものではなく
正規のホスト名に着地することになります — 最初から常に正しいものを使う方がシンプル
です。

### nspawnmgr → auth のリダイレクト

nspawnmgrがセッションクッキーを検証できない場合、ブラウザを、ユーザーが到達しようと
していたページへ戻す`returnTo`クエリパラメータとともに`nspawnmgr.auth.login-url`
（環境変数`AUTH_LOGIN_URL`）にリダイレクトします；`auth.war`はログイン成功後にそこへ
リダイレクトし返します。`login-url`が空欄のままの場合、nspawnmgrは代わりにリダイレクト
なしで独自の静的な「login required」ページを表示します — 完全な自動フローのためには
`AUTH_LOGIN_URL`を`auth`の`/auth/login` URL（例えば`http://<hostname>:8080/auth/login`）
に設定してください。

## 9. nspawnmgrの設定

すべての設定は`src/main/resources/application.yml`の`nspawnmgr.*`配下にあり、それぞれ
環境変数で上書き可能です — 環境変数としての完全なリストについては
`site/env/.env.example`を、同じ設定のYAML版については
`dev_env/application-dev_env.example.yml`を参照してください。重要なグループ：

- **`nspawnmgr.ssh.*`** — [§3](#3-sudo権限を持つsshアカウント)のsudo権限を持つ
  アカウント（`SSH_HOST`/`SSH_PORT`/`SSH_USERNAME`/`SSH_PASSWORD`、ホストは常に
  `127.0.0.1`）、加えて`SSH_PRIVATE_KEY_PATH`、`SSH_CONNECT_TIMEOUT_MS`、
  `SSH_STRICT_HOST_KEY_CHECKING`。`SSH_PASSWORD`を空欄のままにすると、コンテナ
  作成が管理者承認モードに切り替わり、代わりに`SSH_PRIVATE_KEY_PATH`が設定されて
  いることを要求します（SSHトランスポート認証にはどちらにしても*何か*で認証する
  必要があります）。
- **`nspawnmgr.auth.user-is-admin-json`** — 外部管理の管理者ロール向けの任意の
  JsonPath（[§3](#3-sudo権限を持つsshアカウント)）；デフォルトのアプリ管理モード
  （最初にログインしたユーザーが管理者になり、その後`/admin/users`で管理可能）の
  ためには空欄のままにしてください。
- **`nspawnmgr.guacamole.*`** — `base-url`、`admin-username`/`admin-password`、
  `data-source`、`home`（`GUACAMOLE_HOME`、デフォルト`/etc/guacamole`）、
  [§7](#7-guacamole)からのものです。
- **`nspawnmgr.auth.*`** — `user-id-url`（既存のクッキーを`auth`に対して検証します）、
  `cookie-name`、`login-url`（§8のリダイレクト先）、キャッシュ/タイムアウトの
  チューニング、`settings-file`（下記の共有auth設定ファイルが書き込まれる場所 —
  auth.war自身の`auth.settings-file`/`AUTH_SETTINGS_FILE`、デフォルト
  `/etc/nspawnmgr/auth-live/auth-settings.properties`と一致していなければ
  なりません）。
- **`nspawnmgr.nspawn.*`** — [§2](#2-ホストの前提条件)からの`templates-dir`、
  `machines-dir`、`settings-dir`、`privileged-scripts-dir`。
- **`nspawnmgr.dns.upstream-servers`** — dnsmasqが`.internal`でないルックアップを
  転送する、カンマ区切りのIPリテラル、デフォルトは`1.1.1.1,9.9.9.9` —
  [「名前によるコンテナの解決」](#名前によるコンテナの解決)参照。
  `hosts-file`/`upstream-servers-file`（`ContainerDnsSyncService`が書き込む
  ファイル）はデプロイ時のパスであり、ライブ編集はできません。
- **`nspawnmgr.host.external-hostname`**（`HOST_EXTERNAL_HOSTNAME`）—
  [§8](#ホスト名と共有セッションクッキー)からの共有ホスト名；このホストの外側の
  ユーザーが使うものであり、`/admin/settings`のURL「Refresh」ボタンがすべての
  Guacamole/Auth URLに取り込むものです。
- **`nspawnmgr.host.public-address`**（`HOST_PUBLIC_ADDRESS`）— 上記と混同しやすい
  別の設定で、もはやSSH/RDP経路では使われません（`guacd`とnspawnmgr自身の
  レディネスチェックは、今ではMANAGEDコンテナの内部veth
  アドレスに直接ダイヤルします — [コンテナのネットワーキング](#コンテナのネットワーキング)
  参照）。残っている唯一の消費者は、Network Diagnosticsページの
  「HOST_PUBLIC_ADDRESS not loopback」チェックです；このチェックがまだその存在
  価値があるかどうかはフォローアップで見直す価値がありますが、まだ見直されて
  いません。`setup-sudo-account.sh`は、インストール時にこのホストの実際の
  アドレスをここに自動検出・シードし続けます。
- **`nspawnmgr.crypto.secret-key`**（`APP_SECRET_KEY`）— `openssl rand -base64 32`
  で生成してください；nspawnmgrが保存するシークレット（例えばコンテナごとに管理
  するGuacamoleの資格情報）を暗号化するために使われます。これを失う/ローテーション
  すると、古い鍵ですでに暗号化されているものはすべて無効になります。
- **`nspawnmgr.provisioning.*`** — `admin-account-name`（所有者自身のユーザー名が
  使えない場合にnspawnmgrが新しいコンテナ内に作成するフォールバックアカウント —
  下記の`コンテナユーザー`参照）、`rdp-password-length`。
- **`CONTAINER_CLI_EXECUTOR=real`** — 実際のデプロイでは`real`でなければなりません；
  `fake`は開発/CI専用で、上記のコンテナ作成モードにかかわらず、SSH/sudo/パスワード
  にはまったく触れません。コンテキスト起動時にどのSpring Beanが配線されるかを
  選ぶため、実行時にはまったく変更できません — `/admin/settings`には意図的に
  公開されていません：これはデプロイ時の選択であり、`fake`が何をするか
  （すべてのコンテナ操作が黙ったno-opになります）を踏まえると、これをランタイム
  のトグルとして公開するリスクを冒す価値はありません。

`SPRING_PROFILES_ACTIVE=prod`を設定してください — これはローカル開発に使われる
インメモリのフェイクの代わりに、実際のSSHバックエンドのエグゼキューターを有効化
します。

### ライブ編集可能な設定（`/admin/settings`）

上記のグループの一部は、`/admin/settings`（管理者専用）で実行時にも変更できます：
`guacamole.base-url`/`data-source`、`host.external-hostname`/`public-address`、
`http-timeout-ms`を含むすべての`auth.*`フィールド、
`provisioning.admin-account-name`/`rdp-password-length`、`nspawnmgr.ssh.*`、
`nspawnmgr.nspawn.*`、そして`nspawnmgr.dns.upstream-servers`。これらは、
リクエストごとのデータベース読み取りではなく、変更が保存された瞬間にリフレッシュ
される`SettingsService`のインメモリスナップショットにより、以降のすべての
リクエスト/割り当てに即座に効果を持ちます。1つ例外があり、ページ自体にも
記載されています：

- **`nspawnmgr.nspawn.privileged-scripts-dir`**は、そのグループの他のすべてと
  同様に即座に効果を持ちますが、これに一致するよう
  `/etc/sudoers.d/nspawnmgr_exec`のハードコードされたパスも*同時に更新しないと*、
  **すべての**権限を要する操作（コンテナの起動/停止、アウトバウンドアクセスの
  同期、下記のRestart Tomcat）が壊れます — sudoはこの設定に従うのではなく、
  単純に新しいパスをフェイルセーフに拒否します。これについてはライブ検証は
  ありません（これはローカルパスであり、保存時点ではまだ作成されていない
  可能性すらあります）— ページに表示される警告があるだけです。
- **`nspawnmgr.dns.upstream-servers`**は、他のすべてと同様に`SettingsService`
  自身のスナップショットには即座に効果を持ちますが、実際に稼働中のdnsmasqへ
  到達することはそこから1ステップ離れています：`ContainerDnsSyncService`は、
  独自の約15秒のポーリングで新しい値を拾い上げ、
  `/etc/dnsmasq.d/nspawnmgr-upstream.conf`を書き換え、dnsmasqを再起動するだけ
  です — なぜそれが単なるリロードではなく完全な`systemctl restart`なのかに
  ついては[「名前によるコンテナの解決」](#名前によるコンテナの解決)参照。

**それ以外のすべては静的/環境変数/再起動のみのまま**です。意図的なものです：
`nspawnmgr.crypto.secret-key`/`nspawnmgr.guacamole.admin-username`/
`admin-password`（シークレットです。加えて暗号鍵をライブでローテーションすると、
古い鍵ですでに暗号化されているものが無効になります）、そして
`CONTAINER_CLI_EXECUTOR`（上記参照）。Hostsはそもそも静的な設定ではありません
— これらは、それぞれのホスト自身の詳細ページと`/admin/hosts/new`経由で完全に
管理者が管理します（上記の「ホスト：管理者が管理する外部マシン」参照）。

すべての変更は、受け入れられる前に検証されます：
- **Guacamoleベース URL、authユーザーID URL、authログイン URL**：ライブのHTTP
  到達可能性プローブ（404であっても、何らかのレスポンスがあれば到達可能と
  カウントされます — これは、URLが何かリッスンしているものに解決されることだけを
  証明し、認証自体が成功することは証明しません）。
- **5つのJsonPathフィールド**：有効なJsonPath式としてコンパイルされなければ
  なりません。
- **Host public address**：形式のみ（ホスト名/IPの構文）— 意図的にプローブされ
  ません。公開アドレスはこのホストの外側からしか到達できないことが多いためで、
  自己プローブしても何も証明されません。
- Cookie name、cache TTL、admin account name、RDP password lengthは基本的な
  形式/範囲チェックを受けます。
- **`dns.upstream-servers`**：IPリテラル（IPv4またはIPv6）のカンマ区切りの
  リストでなければなりません — ホスト名は拒否されます。dnsmasq自身の`server=`
  ディレクティブは、DNSサーバーなしですでに解決可能な何かを必要とするためです
  （これがdnsmasq自体が他のすべてを解決するために使うものです）。
- **`ssh.*`**：送信された変更にSSHフィールドが1つでも含まれている場合、その
  変更が受け入れられる前に、*結果として得られる*設定で本物のSSH接続が開かれます
  （トランスポートのログインのみ — コマンド実行はしないため、これはNOPASSWD
  sudoersの許可が正しいかどうかには依存しません）。設定ページは常に（他のすべての
  セクションと同様に）すべてのフィールドを一緒に再送信するため、実際にはこれは
  UIからの保存のたびに実行されます — Guacamole/auth URLの到達可能性プローブが
  すでに行っているのと同じ方法です。すべての`ssh.*`キーを省略した部分的な
  ペイロードでAPIを直接呼び出すと、これはスキップされます。

#### 認証セクション（auth.warが検出された場合のみ）

もしauth.warが到達可能に見える場合（`auth.login-url`のライブプローブ）、
`/admin/settings`はauth.war**自身**のバックエンド設定用のセクションも表示します：
`auth.backend`（`pam`/`smb`）、SMBサーバー/ドメイン、そして
[§8](#8-authログインバックエンド)からの required-group/required-share のゲート —
これらは今日、auth.warの`web.xml`のcontext-params/システムプロパティにのみ存在し、
デプロイ時に固定されています。

このセクションを保存すると（上記のcookie name — これもauth.warが合意する必要が
あるものです。実際にクッキーを設定するのはauth.warです — と一緒に）、
`nspawnmgr.auth.settings-file`にある共有プロパティファイルに書き込まれます。
`AuthConfig`は、自身のcontext-params/システムプロパティよりも先に、すべての
リクエストで**最初に**このファイルをチェックします — そのためここでの保存は、
どちらのwebappの再起動も必要とせず、auth.warのまさに次のリクエストで反映されます。
ここで空欄/未設定の値は単に「上書きなし」を意味します；auth.warは、これが存在する
前とまったく同じように、自身の`web.xml`/システムプロパティのデフォルトに
フォールバックします。ファイルの書き込みはベストエフォートです：もし失敗した
場合（例えば手動インストールが[§5](#5-nspawnmgrのインストール)の
`/etc/nspawnmgr/auth-live/`のセットアップをスキップした場合）、データベースへの
保存はそれでも成功し、警告がログに記録されます — 設定の更新の残りはブロック
しません。

#### Guacamoleセクション（条件付き）

もしGuacamoleが到達可能に見える場合（`guacamole.base-url`のライブプローブ）、
`/admin/settings`は`guacamole.properties`（`nspawnmgr.guacamole.home`にあります）
向けの構造化されたエディターも表示します：`guacd-hostname`/`guacd-port`/
`guacd-ssl`それぞれの個別フィールド、加えて、対応する`guacamole-auth-jdbc`拡張が
サポートするすべてのフィールド — 接続、SSL/TLS、パスワードポリシー、コネクション
ごとの同時実行数制限、外部認証統合、そしてアクセスウィンドウの強制 — を明らかに
するデータベースタイプセレクター（MySQL/MariaDBまたはPostgreSQL）。フィールド
ラベルとヘルプテキストは、ローカルで考案されたものではなく、
[Apache Guacamoleマニュアル](https://guacamole.apache.org/doc/gug/configuring-guacamole.html)
（[MySQL](https://guacamole.apache.org/doc/gug/mysql-auth.html) /
[PostgreSQL](https://guacamole.apache.org/doc/gug/postgresql-auth.html)認証拡張
のページ）から直接ソースされています。

このページを読み込むと、既存のファイルが読み取られ、すでに設定されているパスワード
を含むすべてのフィールドが事前入力されます（このアプリの他の場所で保存済みの
資格情報を変更するのと同じ、標準的なマスクされた`<input type="password">`で
レンダリングされます — 画面上で平文が見えるわけではありませんが、これは意図的な
設計上の選択であることに注意してください：シークレットをライブ編集画面から完全に
遠ざける`/admin/settings`の他の部分とは異なり、このエディター全体の目的は、
管理者がSSHせずに既存のGuacamole DB設定を確認・調整できるようにすることです）。
保存は上記に文書化されたキーにのみ触れます：選択*しなかった*方のデータベース拡張の
キーをクリアし（そのためファイルが以前の選択の古い設定を蓄積することはありません）、
すでにファイルにある他のキーはそのまま保持します（例えば手作業で追加された拡張
自身の設定）。保存は、Tomcatを**再起動しません** — あなたが再起動するまで
Guacamoleはこの変更を見ません（`sudo systemctl restart tomcat9`）。

#### 設定レポート

「Download settings report」は、ページ上のすべての設定（加えてデータベース
ウィザードが永続化した`DB_URL`/`DB_USERNAME`/`DB_VENDOR`とGuacamoleの構造化
エディターの現在のファイルの値）を、ページ自体と同じ方法でグループ化した
プレーンテキストファイルとして生成します。パスワードのような形の値
（`ssh.password`、`DB_PASSWORD`、任意のGuacamoleの`*-password`キー）はすべて、
リテラルな`********`に置き換えられます：レポートは値が*設定されていること*だけを
確認し、それが何であるかは決して確認しません。

#### Tomcatの再起動

[§3](#3-sudo権限を持つsshアカウント)からの、他のすべての日常的な権限を要する
操作がすでに使っているのと同じsudo権限を持つSSHアカウントとNOPASSWD sudoersの
許可を経由して`sudo systemctl restart --no-block tomcat9`を発火させます —
`.deb`は必要なラッパースクリプト
（`/usr/lib/nspawnmgr/privileged/nspawnmgr-restart-tomcat.sh`）とsudoers
エントリを自動的に出荷します。手動（非`.deb`）インストールでは、両方を手作業で
追加する必要があります：スクリプトを
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-restart-tomcat.sh`から
`nspawn.privileged-scripts-dir`にコピーし、そのパスを
`/etc/sudoers.d/nspawnmgr_exec`の`NSPAWNMGR_NOPASSWD`エイリアスに追加してください
（信頼する前に`visudo -cf`で検証してください）。

再起動は非同期に発火します（`--no-block`はsystemdジョブをキューに入れ、ほぼ即座に
戻ります）。待機はされません — どのみち待機はうまくいきません。再起動を要求して
いるまさにそのリクエストが、今にも落ちようとしているそのTomcatインスタンスによって
処理されているためです。ボタンをクリックして確認した後、ページは5秒待ち、
クライアント側でセッションクッキーをクリアし、リロードします — （その時点までに
再起動された）アプリが欠けているクッキーを見た時点で、他の期限切れセッションと
まったく同じ方法で、ログインページに戻ります。

## 10. デプロイの検証

**`.deb`インストールでは**（セルフホスト型 — [§1](#1-アーキテクチャ概要)）：下記の
`<hostname>:<port>`は、`postinst`中にインストールが表示したポート（すでに使用中で
ない限り8080）を意味し、`machinectl list`/ログ確認のコマンドには
`sudo machinectl shell nspawnmgr <command>`が必要です — Tomcat、`guacd`、そして
両方のWARのログは、ホスト上ではなく、すべてそのマシンの内側にあります。手動の、
オプションB（ホストTomcat）のインストールでは、下記のすべては代わりに直接ホスト上で
実行されます。以前と同じです。

1. セルフホスト型`nspawnmgr`マシンが立ち上がっていることを確認してください：
   ホスト上の`sudo machinectl list`は、それが`running`であることを示すはずです
   （そして、§4を終えていれば、そのデータベースマシンも）。その内側では、
   `guacd`とTomcat（`nspawnmgr.war` + `guacamole.war` + `auth.war`）の両方が
   稼働しているはずです。
2. `http://<hostname>:<port>/auth/login`に直接アクセスし、§4のウィザード中に
   作成された最初のアカウントでログインできることを確認してください（そして、
   設定していれば、`auth.required-group`/`smb.required-share`の外側のアカウント
   が正しく拒否されることも）。
3. クッキーが存在しない状態で`http://<hostname>:<port>/nspawnmgr/`にアクセス
   してください — `auth`のログインページにリダイレクトされ、ログイン後には
   nspawnmgrに戻されるはずです。`nspawnmgr`/データベースマシンは、この時点で
   すでにコンテナ一覧に通常のコンテナとして表示されているはずです —
   ウィザードがそれらを直接登録するため、最初にログインする必要はありません。
4. nspawnmgrのUI経由で新しいコンテナを作成し、実際に起動すること
   （ホスト上の`sudo machinectl list`にそれが表示されるはずです）と、それに
   対してGuacamole接続が現れることを確認してください。
5. 上記のいずれかが失敗した場合、nspawnmgr自身の「View log」ページ
   （少なくともページを提供できるところまで進んでいれば）、または
   より低レベルの失敗については
   `sudo machinectl shell nspawnmgr journalctl -u tomcat9`を確認してください
   — 最初のデプロイの問題のほとんどは、ホスト名/クッキーの不一致（§8）か、
   sudoアカウント（§3）が実際に正しく設定されたsudo/SSHアクセスを持っていない
   ことです。

## 11. Day-2運用

- **ログ**：単一のTomcatインスタンス（nspawnmgr、Guacamole、authすべてがそこに
  ログを出力します）については`<tomcat-dir>/logs/catalina.out.<date>.log`；
  Guacamoleのプロキシデーモンについては`journalctl -u guacd` — `.deb`
  （セルフホスト型）インストールでは、どちらも*内側*の`nspawnmgr`マシンにあり
  （`sudo machinectl shell nspawnmgr <command>`）、ホスト上ではありません。
  `.deb`は`tomcat9.service`の`ExecStart`経由でTomcat自身の標準出力/標準エラー
  を`rotatelogs`（`apache2-utils`）に通し、日ごとに新しい日付付きファイルを
  生成します — 単なる`catalina.sh start`とは異なり、このパッケージの
  `tomcat9.service`は直接`catalina.sh run`を実行します。これは、日付なしの
  `catalina.out`を自発的に生成することは決してありません（それは、対話的に
  Tomcatを実行する場合、例えば開発スタックでのみ見るものです）。すべての
  ログイン済みユーザーは、nspawnmgr自身の「View log」ページで最新100行と
  現在の完全なログを見ることができます；管理者はそこから、個々のローテート
  された過去の日を参照・削除することもできます。
- **再起動**：`-D`/環境変数の設定を変更した後はTomcatを再起動してください —
  どれもホットリロードされず、3つのwebappすべてが1つのインスタンスを共有して
  いるため、それを再起動すると3つすべてが一緒に再起動されます。
  `guacamole.properties`の`guacd-hostname`/`guacd-port`を変更した後は、
  `guacd`だけを再起動してください。
- **バックアップ**：nspawnmgr自身のデータベース（コンテナ/ユーザーの
  メタデータ）、Guacamole自身のデータベース（接続履歴/パラメーター）、そして
  `/var/lib/machines`（コンテナのルートファイルシステム）を別々にバックアップ
  してください — これらは独立したストアであり、nspawnmgrがアプリケーション
  レベルで管理する以上の相互参照の整合性は強制されません。
- **`APP_SECRET_KEY`のローテーション**：組み込みの再暗号化ツールはありません；
  これを、稼働中のシステムで気軽に変更するものではなく、緊急時対応の、事前に
  計画すべき操作として扱ってください。
- **保留中のコンテナリクエスト**（管理者承認モードのみ）：`/requests`に表示
  されます。`DENIED`は現在終端状態です — 再送信の手段はなく、リクエストした
  ユーザーはゼロから新しいコンテナを作成しなければなりません。
