#!/bin/sh
# One-off setup for a plain Fedora Cloud Base QEMU test VM on acer - separate from
# packaging/ci/fedora-runner/ (that's a systemd-nspawn CI runner container, deliberately kept
# independent of what it's testing). This VM is for manually verifying packaging/nspawnmgr-rpm/'s
# real install (dnf/rpm scriptlet behavior, firewalld/dnsmasq carve-outs) on genuine Fedora bare
# metal/VM, same purpose the SteamOS QEMU VM served for packaging/nspawnmgr-steamos/.
#
# Much simpler than the SteamOS VM: Fedora's own Cloud Base qcow2 image + cloud-init handles
# user/SSH-key provisioning with zero interactive install steps (no GRUB navigation, no Gaming
# Mode bypass, no A/B partition scheme) - boots straight to a real shell reachable over SSH.
#
# Run as a normal user (not root) on acer - only needs permission to write to ~/fedora-test-vm and
# run qemu-system-x86_64/KVM (same group membership the SteamOS VM already relies on).
set -e

WORK_DIR="$HOME/fedora-test-vm"
FEDORA_RELEASE="43"
SSH_PORT="2222"
IMAGE_URL="https://download.fedoraproject.org/pub/fedora/linux/releases/${FEDORA_RELEASE}/Cloud/x86_64/images"

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ -f qemu.pid ] && kill -0 "$(cat qemu.pid)" 2>/dev/null; then
    echo "A QEMU process is already running (pid $(cat qemu.pid)) - refusing to start a second one." >&2
    exit 1
fi

if [ ! -f base.qcow2 ]; then
    echo "Finding the current Fedora $FEDORA_RELEASE Cloud Base image..."
    IMAGE_NAME="$(curl -fsSL "$IMAGE_URL/" | grep -oE "Fedora-Cloud-Base-Generic-${FEDORA_RELEASE}-[0-9.]+\.x86_64\.qcow2" | sort -u | tail -1)"
    if [ -z "$IMAGE_NAME" ]; then
        echo "Couldn't find a Cloud Base image under $IMAGE_URL/ - check the directory listing manually." >&2
        exit 1
    fi
    echo "Downloading $IMAGE_NAME..."
    curl -fsSL -o base.qcow2 "$IMAGE_URL/$IMAGE_NAME"
fi

# Cloud Base images ship a small (~5GB) disk - grow it for real headroom (nspawnmgr's own
# self-hosted machine bootstrap downloads a Debian rootfs + JRE + Tomcat, needs real space beyond
# the base OS). A backing-file overlay (not resizing base.qcow2 directly) keeps the pristine
# download reusable if this VM ever needs recreating from scratch.
if [ ! -f test.qcow2 ]; then
    qemu-img create -f qcow2 -F qcow2 -b base.qcow2 test.qcow2 20G
fi

if [ ! -f ssh_key ]; then
    echo "Generating a fresh SSH keypair for this VM..."
    ssh-keygen -t ed25519 -f ssh_key -N "" -C "fedora-test-vm" -q
fi

echo "Building cloud-init seed (user 'fedora', pubkey auth only, no password)..."
cat > user-data <<EOF
#cloud-config
hostname: fedora-test
users:
  - name: fedora
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    ssh_authorized_keys:
      - $(cat ssh_key.pub)
ssh_pwauth: false
EOF
cat > meta-data <<EOF
instance-id: fedora-test-vm
local-hostname: fedora-test
EOF
genisoimage -output seed.iso -volid cidata -joliet -rock user-data meta-data >/dev/null 2>&1

echo "Launching QEMU (2 vCPU, 2GB RAM - basic dnf/rpm testing doesn't need more)..."
qemu-system-x86_64 \
    -enable-kvm \
    -m 2048 \
    -smp 2 \
    -cpu host \
    -drive if=virtio,file=test.qcow2,format=qcow2 \
    -drive if=virtio,file=seed.iso,format=raw \
    -netdev user,id=net0,hostfwd=tcp:127.0.0.1:${SSH_PORT}-:22 \
    -device virtio-net-pci,netdev=net0 \
    -serial file:serial.log \
    -display none \
    -daemonize \
    -pidfile qemu.pid

echo "Fedora test VM launched (pid $(cat qemu.pid)). Waiting for cloud-init/SSH to come up..."
i=1
while [ "$i" -le 60 ]; do
    if ssh -i ssh_key -p "$SSH_PORT" -o StrictHostKeyChecking=no -o ConnectTimeout=3 -o BatchMode=yes fedora@127.0.0.1 true 2>/dev/null; then
        echo "SSH is up. Reachable via:"
        echo "  ssh -i $WORK_DIR/ssh_key -p $SSH_PORT fedora@127.0.0.1"
        exit 0
    fi
    sleep 5
    i=$((i + 1))
done
echo "SSH didn't come up within 5 minutes - check $WORK_DIR/serial.log for boot output." >&2
exit 1
