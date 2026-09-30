#!/bin/bash
# Builds an SD card image for the Orange Pi 5 Pro from Orange Pi's Android 12 box
# firmware (a Rockchip update.img meant for the Windows-only SDDiskTool), set up as
# a PS5 Remote Play device.
#
#   build-image.sh <chiaki.apk> <card size in 512 byte sectors> <output.img>
set -euo pipefail

APK=$(realpath "$1")
CARD_SECTORS=$2
OUT=$(realpath -m "$3")

HERE=$(cd "$(dirname "$0")" && pwd)
W=$HERE/work
T=$W/tools/usr/bin
FW=$W/fw
MOD=$W/mod
ADB_PUBKEY=${ADB_PUBKEY:-$HOME/.android/adbkey.pub}

rm -rf "$MOD"
mkdir -p "$MOD"
cp "$W"/parts/*.img "$MOD"/

# debugfs helpers: files get root ownership, the given mode and SELinux label
debugfs_batch() { # <image> <command file>
	# rm of a file that doesn't exist yet is expected to fail
	debugfs -w -f "$2" "$1" 2>&1 | grep -viE '^debugfs [0-9]|^$|^rm:' | grep -iE 'error|not found|fail|could not|no free' && {
		echo "debugfs failed on $1" >&2; exit 1; } || true
}
label_file() { # <label> -> path of a file holding the label with the trailing NUL
	local f=$MOD/label_${1//[^a-z_]/_}
	printf '%s\0' "$1" > "$f"
	echo "$f"
}
put_file() { # <cmdfile> <local file> <path in image> <mode> <label>
	local lf
	lf=$(label_file "$5")
	cat >> "$1" <<-EOF
		rm $3
		write $2 $3
		sif $3 uid 0
		sif $3 gid 0
		sif $3 mode 0100$4
		ea_set -f $lf $3 security.selinux
	EOF
}
put_dir() { # <cmdfile> <path in image> <label>
	local lf
	lf=$(label_file "$3")
	cat >> "$1" <<-EOF
		mkdir $2
		sif $2 uid 0
		sif $2 gid 0
		sif $2 mode 040755
		ea_set -f $lf $2 security.selinux
	EOF
}

SYS_LABEL=u:object_r:system_file:s0

echo "== system: DualSense/DualShock 4 input configs, ADB key"
cmds=$MOD/system.cmds; : > "$cmds"
# The DualSense touchpad is a mouse pointer by default. As a touch navigation device its
# fingers reach the app as absolute touches, which Momiji forwards to the console's
# touchpad. The motion sensors are described like Android does for the DualShock 4.
for pid in 0ce6 0df2 05c4 09cc; do
	cat > "$MOD/Vendor_054c_Product_$pid.idc" <<-'EOF'
		# Sony PlayStation controller: motion sensors and touchpad for PS Remote Play
		sensor.accelerometer.reportingMode = 0
		sensor.accelerometer.maxDelay = 100000
		sensor.accelerometer.minDelay = 5000
		sensor.accelerometer.power = 1.5
		sensor.gyroscope.reportingMode = 0
		sensor.gyroscope.maxDelay = 100000
		sensor.gyroscope.minDelay = 5000
		sensor.gyroscope.power = 0.8
		touch.deviceType = touchNavigation
	EOF
	put_file "$cmds" "$MOD/Vendor_054c_Product_$pid.idc" "/system/usr/idc/Vendor_054c_Product_$pid.idc" 644 $SYS_LABEL
done
# Only this PC may use ADB (over the network on port 5555); others need approval on the TV
debugfs -R "dump /system/build.prop $MOD/build.prop" "$MOD/system.img" 2>/dev/null
grep -q '^ro.adb.secure=' "$MOD/build.prop" || printf '\n# PS Remote Play setup\nro.adb.secure=1\n' >> "$MOD/build.prop"
put_file "$cmds" "$MOD/build.prop" /system/build.prop 600 $SYS_LABEL
cp "$ADB_PUBKEY" "$MOD/adb_keys"
put_file "$cmds" "$MOD/adb_keys" /adb_keys 644 u:object_r:adb_keys_file:s0
debugfs_batch "$MOD/system.img" "$cmds"

echo "== vendor: let apps talk to controllers directly (adaptive triggers, lightbar)"
cmds=$MOD/vendor.cmds; : > "$cmds"
debugfs -R "dump /etc/ueventd.rc $MOD/ueventd.rc" "$MOD/vendor.img" 2>/dev/null
grep -q 'PS Remote Play' "$MOD/ueventd.rc" || cat >> "$MOD/ueventd.rc" <<-'EOF'

	# PS Remote Play: controller HID access for adaptive triggers and the lightbar
	/dev/hidraw*              0666   system     input
EOF
put_file "$cmds" "$MOD/ueventd.rc" /etc/ueventd.rc 644 u:object_r:vendor_configs_file:s0
debugfs_batch "$MOD/vendor.img" "$cmds"

echo "== product: Momiji as a system app"
e2fsck -fy "$MOD/product.img" >/dev/null || [ $? -le 1 ]
blocks=$(dumpe2fs -h "$MOD/product.img" 2>/dev/null | awk '/^Block count/ {print $3}')
apk_blocks=$(( ($(stat -c %s "$APK") + 4095) / 4096 ))
resize2fs "$MOD/product.img" $(( blocks + apk_blocks * 3 + 4096 )) >/dev/null 2>&1
cmds=$MOD/product.cmds; : > "$cmds"
put_dir "$cmds" /app/Momiji $SYS_LABEL
put_file "$cmds" "$APK" /app/Momiji/Momiji.apk 644 $SYS_LABEL
put_dir "$cmds" /etc/security $SYS_LABEL
put_file "$cmds" "$MOD/adb_keys" /etc/security/adb_keys 644 $SYS_LABEL
debugfs_batch "$MOD/product.img" "$cmds"
for p in system vendor product; do
	e2fsck -fn "$MOD/$p.img" >/dev/null 2>&1 || { echo "e2fsck found problems in $p.img" >&2; exit 1; }
done

echo "== super"
super_size=$(( 0x614000 * 512 ))
args=(--metadata-size 65536 --super-name super --metadata-slots 2
	--device super:$super_size --group rockchip_dynamic_partitions:3258974208)
for p in system system_ext vendor vendor_dlkm odm odm_dlkm product; do
	args+=(--partition "$p:readonly:$(stat -c %s "$MOD/$p.img"):rockchip_dynamic_partitions"
		--image "$p=$MOD/$p.img")
done
"$T/lpmake" "${args[@]}" --output "$MOD/super.raw" >/dev/null

echo "== SPI flash fallback"
# Only runs when a Linux U-Boot in the SPI flash takes over the boot (Android's U-Boot
# doesn't look at this partition): it clears the flash so the board boots the SD card.
cat > "$MOD/boot.cmd" <<-'EOF'
	echo "PS Remote Play SD card: checking the SPI flash"
	if sf probe; then
		for off in 0x0 0x8000; do
			if sf read ${kernel_addr_r} ${off} 0x200 && itest.l *${kernel_addr_r} == 0x534e4b52; then
				echo "A bootloader in the SPI flash keeps Android from booting off the SD card."
				echo "Erasing the SPI flash, afterwards the board boots from the SD card by itself..."
				sf erase 0 0x400000
				echo "Done, restarting"
				sleep 3
				reset
			fi
		done
	fi
	echo "No bootloader to remove from the SPI flash"
EOF
"$T/mkimage" -A arm64 -O linux -T script -C none -d "$MOD/boot.cmd" "$MOD/boot.scr" >/dev/null
rm -f "$MOD/spifix.img"
mkfs.vfat -n SPIFIX -C "$MOD/spifix.img" 16384 >/dev/null
MTOOLS_SKIP_CHECK=1 mcopy -i "$MOD/spifix.img" "$MOD/boot.scr" "$MOD/boot.cmd" ::/

echo "== disk image"
spifix_size=32768
spifix_start=$(( (CARD_SECTORS - 34 - spifix_size) / 2048 * 2048 ))
rm -f "$OUT"
truncate -s $(( CARD_SECTORS * 512 )) "$OUT"
# Partitions exactly as Rockchip's parameter.txt defines them; userdata takes the rest
{
	echo "label: gpt"
	echo "first-lba: 34"
	echo "last-lba: $(( CARD_SECTORS - 34 ))"
	L=0FC63DAF-8483-4772-8E79-3D69D8477DE4
	while read -r size start name; do
		echo "start=$(( start )), size=$(( size )), type=$L, name=\"$name\""
	done <<-EOF
		0x2000 0x2000 security
		0x3000 0x4000 uboot
		0x2000 0x7000 trust
		0x2000 0x9000 misc
		0x2000 0xb000 dtbo
		0x800 0xd000 vbmeta
		0x32000 0xd800 boot
		0x36000 0x3f800 recovery
		0xba000 0x75800 backup
		0xc0000 0x12f800 cache
		0x8000 0x1ef800 metadata
		0x800 0x1f7800 baseparameter
		0x8000 0x1f8000 logo
		0x614000 0x200000 super
	EOF
	echo "start=$(( 0x814000 )), size=$(( spifix_start - 0x814000 )), type=$L, name=\"userdata\""
	echo "start=$spifix_start, size=$spifix_size, type=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7, name=\"spifix\", attrs=\"LegacyBIOSBootable\""
} | sfdisk -q --no-reread --no-tell-kernel "$OUT"

put() { dd if="$1" of="$OUT" bs=512 seek=$(( $2 )) conv=notrunc,fsync status=none; }
zero() { dd if=/dev/zero of="$OUT" bs=512 seek=$(( $1 )) count=$(( $2 )) conv=notrunc status=none; }
put "$W/idblock.bin" 64
# Stale data from whatever was on the card before must not look like a filesystem
for region in "0x2000 0x2000" "0x7000 0x2000" "0x75800 0x800" "0x12f800 0x4000" \
	"0x1ef800 0x8000" "0x1f8000 0x8000" "0x814000 0x20000"; do
	zero $region
done
put "$FW/uboot.img" 0x4000
put "$FW/misc.img" 0x9000
put "$FW/dtbo.img" 0xb000
put "$FW/vbmeta.img" 0xd000
put "$FW/boot.img" 0xd800
put "$FW/recovery.img" 0x3f800
put "$FW/baseparameter.img" 0x1f7800
put "$MOD/super.raw" 0x200000
put "$MOD/spifix.img" $spifix_start

sfdisk -l "$OUT"
echo "Image ready: $OUT"
