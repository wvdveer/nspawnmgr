#!/bin/sh
# (Re)writes VM $1's persistent systemd unit file and reloads systemd - called once at creation
# (right after nspawnmgr-qemu-create-disk.sh) and again any time the mounted ISO changes while the
# VM is stopped (see ContainerIsoMounter's QEMU branch), so the next plain `systemctl start
# nspawnmgr-qemu-<name>.service` always launches with current settings. Deliberately a REAL unit
# file, not a transient `systemd-run --collect` one re-issued on every start: ContainerCliExecutor's
# own start/stop/restart/status all take just a bare machine name (no VM-specific data), so there
# would be nothing to rebuild a transient invocation *from* - see
# project_qemu_lifecycle_design_corrections memory for why this differs from the original plan.
# NOPASSWD (a fixed-shape settings-file rewrite, not creation of a new persistent artifact - matches
# nspawnmgr-podman-create-container.sh's own writeNspawnSettings-equivalent NOPASSWD tier).
#
# $1 = VM name, $2 = VNC TCP port (on 10.100.0.1 - see ProvisioningService#allocateQemuVncPort;
#      must be >=5900, since `-vnc host:display` addresses a display number, not a port directly -
#      display = port - 5900), $3 = optional host-side ISO path (blank = boot from disk only),
# $4 = optional -cpu model (blank = no -cpu flag, QEMU's own default), $5 = optional CPU count
# (blank = no -smp flag, single CPU), $6 = optional memory in MB (blank = 2048), $7 = optional NIC
# device model (blank = virtio-net-pci), $8 = optional extra pointer-device flags (blank = PS/2
# only, QEMU's own machine default - DOS-family guests have no USB driver stack and need this;
# "-usb -device usb-tablet" adds an absolute-positioning USB tablet, which keeps the guest cursor
# in sync with VNC's own absolute host cursor - see QemuPointerDevice). Every caller must now pass
# all 8 positions explicitly (empty string for "unset") - unlike $3 alone, these can no longer be
# omitted from the end of the argv once there are optional positions after them.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-qemu-write-unit.sh must be run as root." >&2
    exit 1
fi

name="$1"
vnc_port="$2"
iso_path="$3"
cpu_arg="$4"
cpu_count="$5"
mem_mb="${6:-2048}"
nic_model="${7:-virtio-net-pci}"
pointer_flags="${8:-}"

disk_path="/var/lib/nspawnmgr/qemu-disks/$name.qcow2"
if [ ! -e "$disk_path" ]; then
    echo "No disk found for '$name' at $disk_path - run nspawnmgr-qemu-create-disk.sh first." >&2
    exit 1
fi

socket_dir="/var/lib/nspawnmgr/qemu-sockets"
mkdir -p "$socket_dir"
monitor_socket="$socket_dir/$name.monitor.sock"

# Same Fedora/RHEL /usr/libexec/qemu-kvm fallback already used by nspawnmgr-diag-check-qemu.sh -
# that package doesn't put a qemu-system-x86_64 on PATH at all (see that script's own comment).
if command -v qemu-system-x86_64 >/dev/null 2>&1; then
    qemu_bin="qemu-system-x86_64"
elif [ -x /usr/libexec/qemu-kvm ]; then
    qemu_bin="/usr/libexec/qemu-kvm"
else
    echo "QEMU isn't installed - see the Diagnostics page." >&2
    exit 1
fi

# Deterministic MAC from the VM name: 52:54:00 is the conventional QEMU/libvirt
# locally-administered OUI, the remaining 3 bytes are the first 3 bytes of an md5 hash of the name.
# nspawnmgr-get-qemu-internal-address.sh MUST derive this identically - the two scripts agree on the
# formula rather than either persisting it anywhere.
mac_suffix=$(printf '%s' "$name" | md5sum | cut -c1-6 | sed 's/\(..\)\(..\)\(..\)/\1:\2:\3/')
mac="52:54:00:$mac_suffix"

# display = port - 5900 (QEMU's -vnc host:display syntax addresses a display number; port 5900 is
# display 0). The admin-configured port range is validated to start at >=5900 for exactly this
# reason (see SettingsService's own validation).
display=$((vnc_port - 5900))

kvm_flag=""
if [ -e /dev/kvm ]; then
    kvm_flag="-enable-kvm"
fi

cpu_flag=""
if [ -n "$cpu_arg" ]; then
    cpu_flag="-cpu $cpu_arg"
fi

smp_flag=""
if [ -n "$cpu_count" ]; then
    smp_flag="-smp $cpu_count"
fi

if [ -n "$iso_path" ]; then
    boot_args="-cdrom $iso_path -boot order=d"
else
    boot_args="-boot order=c"
fi

unit_path="/etc/systemd/system/nspawnmgr-qemu-$name.service"
cat > "$unit_path" <<EOF
[Unit]
Description=nspawnmgr QEMU VM $name

[Service]
Type=simple
ExecStart=$qemu_bin -name $name -m $mem_mb $cpu_flag $smp_flag $kvm_flag \\
  -drive file=$disk_path,format=qcow2,if=virtio \\
  -netdev bridge,br=nspawnbr0,id=net0 -device $nic_model,netdev=net0,mac=$mac \\
  $pointer_flags \\
  -vnc 10.100.0.1:$display \\
  -monitor unix:$monitor_socket,server=on,wait=off \\
  $boot_args
EOF

systemctl daemon-reload
