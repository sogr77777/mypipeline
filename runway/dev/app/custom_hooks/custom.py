#!/usr/bin/env python
"""Helper classes for Stacker AWS Lambda hook."""
import boto3
import os


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


def upload(bucket, destination, local_directory, region, context, provider, **kwargs):  # pylint: disable=unused-argument
    local_directory = local_directory
    bucket = bucket
    destination = destination

    client = boto3.client('s3', region_name=region)

    # enumerate local files recursively
    for root, dirs, files in os.walk(local_directory):

        for filename in files:

            # construct the full local path
            local_path = os.path.join(root, filename)

            # construct the full Dropbox path
            relative_path = os.path.relpath(local_path, local_directory)
            s3_path = os.path.join(destination, relative_path)

            # relative_path = os.path.relpath(os.path.join(root, filename))

            print 'Searching "%s" in "%s"' % (s3_path, bucket)
            try:
                client.head_object(Bucket=bucket, Key=s3_path)

                try:
                    print "Path found on S3 - %s..." % s3_path
                    client.delete_object(Bucket=bucket, Key=s3_path)
                    print "Reuploading %s..." % s3_path
                    client.upload_file(local_path, bucket, s3_path)
                except:
                    print "Unable to delete %s..." % s3_path
            except:
                print "Uploading %s..." % s3_path
                client.upload_file(local_path, bucket, s3_path)

    return "Done"
