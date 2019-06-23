#!/bin/bash
echo entering disk format
mkfs -t ext4 /dev/xvdb
echo exiting disk format
echo entering swap
mkswap /dev/xvds
swapon /dev/xvds
echo exiting swap
echo entering mkdir
mkdir /u02
echo entering mount u02
mount /dev/xvdb /u02
echo exiting mount u02
cp /etc/fstab /etc/fstab.orig
echo '/dev/xvdb               /u02           ext4    defaults        0 0' >> /etc/fstab
echo '/dev/xvds               none           swap    sw              0 0' >> /etc/fstab