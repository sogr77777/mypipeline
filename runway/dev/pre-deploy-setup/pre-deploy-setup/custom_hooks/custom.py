#!/usr/bin/env python
"""Helper classes for Stacker AWS Lambda hook."""
import boto3


def termprotection(stacks, regions, context, provider, **kwargs):
    print "INFO: Running termination protection"
    for region in regions:
        client = boto3.client('cloudformation', region_name=region)

        for stack in stacks:
            try:
                client.update_termination_protection(
                    EnableTerminationProtection=True,
                    StackName=stack
                )
                print "Termination protection enabled for " + stack + " in " + region
            except:
                print "TERMINATION PROTECTION FAILD FOR " + stack + " in " + region

    return "SUCCESS"
