#!/bin/bash

export AMIID='ami-fad9ef9f'
export AMI_NAME="Training-Example-RHEL"
export REGION='us-east-2'
export SUBNET='subnet-d15a6eb8'
export SECURITY_GROUPS='sg-643b1f0c'
export BUILD_NUMBER='01'

export APPNAME_TAG='example'
export COSTCENTER_TAG='1001700224'
export APPID_TAG='1005238'
export ENV_TAG='dev'
export OWNER_TAG='victor_mao@baxter.com'

unset AMIID
unset AMI_NAME
unset REGION
unset SUBNET
unset SECURITY_GROUPS
unset BUILD_NUMBER

unset APPNAME_TAG
unset COSTCENTER_TAG
unset APPID_TAG
unset ENV_TAG
unset OWNER_TAG