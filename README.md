[//]: # (This is the README.md file for au.com.ecetera.sbt sbt-web-s3 plugin)

# sbt-web-s3

### Status
* Currently a 0.2.1-SNAPSHOT build and has not been release to a repo yet.
* Therefore not ready for production.
* To use it you'll need to build it locally.
* It does publish to S3.
* Incremental mode works so now with s3wsSync - so only changed files go up and deleted file are removed.
* sw3Prefix now enables publishing and deleting from a "directory" or "folder" in your bucket.

## Description

This AutoPlugin's main goal is to allow you to publish compressed static website assets to an Amazon AWS S3 bucket.
By default all the files found in the s3wsAssetDir will be uploaded to the specified bucket. Files that can
be compressed will be gzip'ed on upload and the S3 ObjectMetadata (aka the Content-Encoding header) will be set
so that a browser will expand the file on render.

### [sbt-web](https://github.com/sbt/sbt-web) interaction
While this plugin is not an sbt-web pipeline plugin its default configuration is designed to work with sbt-web piplines
For example the default location it searches for web assets is the default `webStage` directory ie `./target/web/stage`.
So a typical usecase would be to run sbt-web's `webStage` task which places all the ready to publish web assets in `target/web/stage`
and then run `s3wsSync` to synchronize the staged contents with the contents to the S3 bucket.

### Other info
* The plugin is an AutoPlugin, so it is designed to be used by sbt 0.13.5 or above. If you're not sure what version of sbt you are using
 run
    $ sbt sbtVersion
 You could also copy the ./sbt script to the root of your project and use it to run sbt.
* In order to see the results of using the plugin as a website you should follow the AWS instructions on
[setting up the bucket to serve a static website at](http://docs.aws.amazon.com/AmazonS3/latest/dev/WebsiteHosting.html)
* You will also need to [get your amazon credentials and secret key](http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSGettingStartedGuide/AWSCredentials.html)
 in order to let the plugin connect and upload files into your bucket. See the example below to see how to use these credentials.

For this help use:-

    $ ./sbt s3wsReadme

## Usage

Add to your project/plugin.sbt the following line:-

    addSbtPlugin("au.com.ecetera.sbt" %% "sbt-web-s3" % "0.2.1-SNAPSHOT")

Add to your build.sbt file the following line:-

    import au.com.ecetera.sbt.S3WebsitePlugin.S3WS._
    
    enablePlugins(S3WebsitePlugin)

You will then be able to use the task `s3wsSync` or `s3wsUpload` defined
in the nested object `au.com.ecetera.sbt.S3WebsitePlugin.S3WS`.
All these operations will use HTTPS as a transport protocol.

Please check the Scaladoc API of the `S3WebsitePlugin` object, and of its nested `S3WS` object,
to get additional documentation of the available sbt tasks.

## Task Descriptions
    s3wsUpload : this task will use your Settings (see below) to upload Web assets to your S3 bucket. By default only
                 new or modified files will be uploaded (see s3wsIncremental setting below).

    s3wsDeleteAll : This task will use your settings to delete all objects from your S3 bucket

    s3wsSync : this task will use your settings below to run s3wsUpload but also will delete files from S3 that have
               been removed locally.

    s3wsDeleteWhat : shows what files s3wsSync will delete.

## Setting Descriptions

    s3wsPrefix in s3wsUpload : Defaults to empty string "". Adds a prefix to all files when added to the s3 bucket. For a static website this is like
                 putting all the files in a subdirectory of the webserver. eg

      s3wsPrefix in s3wsUpload := "mydir/"

                 will result in all the files ending up under <s3bucketname>/mydir/

    s3wsPrefix in s3wsDeleteAll : Defaults to empty string "". Adds a prefix when searching for files to delete in s3. Only files with that prefix
                 will be deleted. Normally the prefix will be the same one used in s3wsPrefix in s3wsUpload


    s3wsIncremental : Boolean, defaults to true. If true only publishes the files that have changed since last time
                      s3Upload was run. Normally used with s3wsSync which also deletes remote S3 files that you have
                      removed locally.

    s3wsWithCompression : Boolean, defaults to true. If true we compress eligible files.

    s3wsLeaveAsIs : Sequence of Files that we don't want to modify on upload,
                    ie files that would normally be compressed but we don't want them to be, for example if the file
                    is so small that the cost of decompressing is more than the time gained by downloading
                    over the network.

    s3wsAssetDir : Base dir for Web assets to publish.
                   Defaults to the sbt-web stagingDirectory: ./target/web/stage.
                   NB that it doesn't not depend on the sbt-web plugin but is intended to work with it, ie
                   if you run webStage from sbt-web then all you assets will be sitting in ./target/web/stage waiting
                   for you to run s3wsSync or s3wsUpload to push to S3 bucket.

    bucket in s3wsUpload : S3 bucket used by the s3wsUpload task, either "mybucket.s3.amazonaws.com" or "mybucket".

    bucket in s3wsDeleteAll : S3 bucket used by the s3wsDeleteAll task, either "mybucket.s3.amazonaws.com" or "mybucket".

    progressBar in s3wsUpload : Boolean, defaults to false. Set to true to get a progress indicator during S3 uploads/downloads.

    credentials : a Seq(Credential(fileLocation)), see Example 1 below for usage.


## Example 1

Here is a complete example:

project/plugin.sbt:

    addSbtPlugin("au.com.ecetera.sbt" % "sbt-web-s3" % "0.1.0-SNAPSHOT")

build.sbt:

    import _root_.au.com.ecetera.sbt.S3WebsitePlugin.S3WS._

    enablePlugins(S3WebsitePlugin)

    bucket in s3wsUpload := "your-bucket.s3.amazonaws.com"

    bucket in s3wsDeleteAll := "your-bucket.s3.amazonaws.com"

    progressBar in s3wsUpload := true

    credentials += Credentials(Path.userHome / ".s3credentials")

~/.s3credentials:

    realm=Amazon S3
    host=your-bucket.s3.amazonaws.com
    user=<Access Key ID>
    password=<Secret Access Key>

Just create two sample files called "index.html" and "./js/myscript,js" in the s3wsAssetDir directory (ie ./target/web/stage), then try:

    $ sbt s3wsUpload

assuming you have (progressBar in upload) set to true , which is recommended only for testing, you will see progress
on upload.

    $ sbt
    > s3wsUpload
    [==================================================]   100%   index.html
    [=====================================>            ]    74%   /js/myscript.js

## Example 2
If you are not using sbt-web plugin and want to change the default s3wsAssetDir to ./web, say, then you can add this to your build.sbt

build.sbt:

    s3wsAssetDir := baseDirectory.value / "web"

## Example 3
You want to push, sync and delete from a subdirectory of the bucket, for example your company may have a bucket mapped to
    http://users.mycompany.com

and you want to post your webpage to
    http://users.mycompany.com/karlstuff

but you don't want to accidentally modify or delete anyone else's web sites that may be sharing the bucket. In this case you need
an sw3sPrefix to make sure that all the stuff under you s3wsAssetDir gets put into the karlstuff/ subdirectory of the
bucket. So As well as you other settings add the following

build.sbt:

    s3wsPrefix in s3wsUpload :- "karlstuff/"

    s3wsPrefix in s3wsUpload :- "karlstuff/"

for example at Ecetera we use this to publish deleloper's tech talks slide decks to subdirectories of
[our techtalks bucket](http://techtalks.ecetera.com.au)

## Bug fixes
For bug fixes or suggestions please use the [Github issues link](https://github.com/Ecetera/sbt-web-s3/issues)

## Known issues
Incremental mode (default, s3wsIncremental := true) relies on a file called ./target/s3ws.lastupload which holds a
timestamp of the last time s3wsUpload or s3wsSync were run. If a file is s3wsAssetDir has been modified since it will be
uploaded. If you run clean which deletes that file then all files will be uploaded again, whether they have been
touched or not.

## Develop
Clone this repo.

    $ git clone https://github.com/Ecetera/sbt-web-s3.git

To Build

    $ ./sbt

NB currently this is not published anywhere so to use it you need to publishLocal
as a helper the Makefile can do this for you, or simply type "publishLocal" from the sbt prompt.

    $ make

Have fun.

