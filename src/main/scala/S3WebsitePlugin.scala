package au.com.ecetera.sbt


import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ProgressEvent, ProgressListener, PutObjectRequest}
import sbt._
import Keys._
import spray.http.MediaType



object S3WebsitePlugin extends sbt.AutoPlugin {


  // This object namespaces all our settings
  object S3WS {


    import com.amazonaws.services.s3.model.DeleteObjectsResult

    import scala.util.Try

    lazy val s3wsUpload = taskKey[Unit]("Upload web assets to AWS S3")

    /** Task that tries to delete allmobjects in the bucket.
     *
     */
    lazy val s3wsDeleteAll = taskKey[Try[DeleteObjectsResult]]("Delete all Objects from the bucket specified by bucket")

    lazy val s3wsDeleteWhat = taskKey[Try[Set[S3Key]]]("Show What will be deleted from S3 on Next Upload to keep in sync with AssetDir")

    lazy val s3wsDeleteDiff = taskKey[Try[DeleteObjectsResult]]("Deleted from S3 locally deleted assets to keep in sync with AssetDir")

    lazy val s3wsSync = taskKey[Unit]("remove stuff that has been deleted and upload web stage dir")

    lazy val s3wsUploadInfo = taskKey[(Seq[(File, String)], MetadataMap)]("Info on what files will be uploaded to where, and with what ObjectMetadata.\n NB this task is used internaly. To see a report of what will happen run `s3wsCheckInfo` instead.")

    /** A string showing the files to be uploaded and the ObjectMetadata to be applied in a human readable way.
      */
    lazy val s3wsCheckInfo = taskKey[String]("display the Upload info in a readable way... TODO")

    lazy val s3wsIncremental = settingKey[Boolean]("Boolean, defaults to true. If true only publishes the files that have changed since last time s3Upload was run.")

    lazy val s3wsWithCompression = settingKey[Boolean]("Boolean, defaults to true. If true we compress eligible files.")

    lazy val s3wsLeaveAsIs = settingKey[Seq[File]]("Sequence of Files that we don't want to modify on upload,\nie files that would normally be compressed but we don't want them to be.")

    lazy val s3wsAssetDir = settingKey[File]("Base dir for Web assets to publish.\nDefaults to the sbt-web stagingDirectory: target.value/web/stage.\n NB that it doesn't not depend on the sbt-web plugin but is intended to work with it.")

    lazy val s3wsPrefix = settingKey[S3Prefix]("Prefix to prepend to file keys in S3 bucket. Defaults to empty string. When deleting only keys with this prefix will be removed")
    /**
     * A string representing the S3 bucket name, in one of two forms:
     *  1. "mybucket.s3.amazonaws.com", where "mybucket" is the bucket name, or
     *  1. "mybucket"
     */
    lazy val bucket=settingKey[String]("Host used by the s3wsUpload operation, either \"mybucket.s3.amazonaws.com\" or \"mybucket\".")

    /**
     * If you set "progressBar" to true, a progress indicator will be displayed while the individual files are uploaded or downloaded.
     * Only recommended for interactive use or testing; the default value is false.
     */
    val progressBar =settingKey[Boolean]("Set to true to get a progress indicator during S3 uploads/downloads (default false).")
  }

  import S3WS._
  import java.util.{List => JList}
  import com.amazonaws.services.s3.internal
  import com.amazonaws.services.s3.model.DeleteObjectsResult
  import com.amazonaws.services.s3.model.S3ObjectSummary

  import scala.util.Try

  override def projectSettings = Seq (
    s3wsWithCompression          := true,
    s3wsIncremental              := false,
    s3wsLeaveAsIs                := Seq(),
    s3wsAssetDir                 := target.value / "web" / "stage",
    s3wsUploadInfo               := {
      S3Publish.calculateFilesAndMetadata(s3wsAssetDir.value, target.value / "s3ws.lastupload",
        target.value / "modfiles",
        s3wsWithCompression.value,
        s3wsLeaveAsIs.value,
        s3wsIncremental.value /*, streams.value.log */ )
    },
    bucket in s3wsUpload         := "",
    bucket in s3wsDeleteAll      := "",
    s3wsPrefix in s3wsUpload     := "",
    s3wsPrefix in s3wsDeleteAll  := "",
    progressBar in s3wsUpload := false,
    s3wsDeleteWhat <<= initDeleteWhat(s3ObjectSummaries),
    s3wsDeleteDiff <<= initDeleteDiff(
      delKeysFromS3,
      { (bucket,key) =>          "I am Deleting "+key+" from "+bucket },
      { (bucket) =>        "Deleted objects from the S3 bucket \""+bucket+"\"." }
    ),

    s3wsSync := {s3wsDeleteDiff.value; s3wsUpload.value},


    s3wsUpload                <<= initUpload(s3wsUpload,
      { case (client,bucket, prefix, (file,key),metadataMap,progress) =>
        val theS3Key = if(prefix.isEmpty) key else {prefix + key}
        val mdo = metadataMap.get(key)
        val request=new PutObjectRequest(bucket,theS3Key,file)
        if (progress) addProgressListener(request,file.length(),theS3Key)
        if (mdo.isDefined) {
            client.putObject(request.withMetadata(mdo.get))
          } else client.putObject(request)
      },
      { case (bucket,(file,key)) =>  "Uploading "+file.getAbsolutePath()+" as "+key+" into "+bucket  },
      {      (bucket,mapps) =>       "Uploaded "+mapps.length+" files to the S3 bucket \""+bucket+"\"." }
    ),


    s3wsDeleteAll <<= initDeleteAll(
      s3wsDeleteAll,
      deleteAllFromS3,
      { (bucket,key) =>          "I am Deleting "+key+" from "+bucket },
      { (bucket) =>        "Deleted objects from the S3 bucket \""+bucket+"\"." }
    )
  ) //endProjectSettings




  /**
   * Helper function to prepare an upload task.
   * @param thisTask The task to prepare, passed in so we can limit scope to it.
   * @param op The Function that operated the AWS API for an individual file to upload.
   * @param msg
   * @param lastMsg
   * @return
   */
  def initUpload(
                  thisTask:TaskKey[Unit],
                  op: (AmazonS3Client, S3Bucket, S3Prefix, (File,String), MetadataMap, Boolean) => Unit,
                  msg:(S3Bucket,(File,String))=>String, lastMsg:(S3Bucket,Seq[(File,String)])=>String) =
    (target in thisTask, s3wsUploadInfo in thisTask, credentials in thisTask, bucket in thisTask, s3wsPrefix in thisTask, progressBar in thisTask, s3wsIncremental in thisTask,  streams ) map {
      (target, uploaddata, creds, host, prefix, progress, incremental, streams) => {
        S3Publish(creds, uploaddata._1, host, prefix, progress, streams, uploaddata._2, op, msg, lastMsg, target / "s3ws.lastupload" )
      }
  }

  def initDeleteAll(thisTask:TaskKey[Try[DeleteObjectsResult]],
                 op: (AmazonS3Client, S3Bucket, S3Prefix) => Try[DeleteObjectsResult],
                 msg:(S3Bucket,String)=>String, lastMsg:(S3Bucket)=>String ) =
    (credentials in thisTask, bucket in thisTask, s3wsPrefix in thisTask, streams ) map {
      (creds, bucket, prefix, streams) =>
        S3Publish.deleteAll(creds, bucket, prefix, streams, op, msg, lastMsg)
    }

  def initDeleteDiff(op:(AmazonS3Client, S3Bucket, Set[S3Key]) => Try[DeleteObjectsResult],
                     msg:(S3Bucket,String)=>String, lastMsg:(S3Bucket)=>String ) =
    (credentials in s3wsDeleteAll, bucket in s3wsDeleteAll, s3wsDeleteWhat, streams) map {
      (creds,bucket, delWhat, streams) =>
        S3Publish.deleteFromS3(creds, bucket, delWhat, streams, op, msg, lastMsg)
    }

  def initDeleteWhat(op: (AmazonS3Client, S3Bucket, S3Prefix) => Try[List[S3ObjectSummary]]) =
    (s3wsAssetDir in s3wsUpload, credentials in s3wsUpload, bucket in s3wsUpload, s3wsPrefix in s3wsUpload, streams ) map {
      (assetDir, creds, bucket, prefix, streams) =>
        val toDel = S3Publish.diffWithLocal(assetDir, creds, bucket, prefix, op)
        val ret = toDel.map(_.fold("")((acc, s) => acc ++ s"will del -> $s")) getOrElse ("Oops Was unable to see what should be deleted")
        streams.log.info(ret)
        toDel
    }


  private def doProgressBar(percent:Int) = {
    val b="=================================================="
    val s="                                                 "
    val p=percent/2
    val z:StringBuilder=new StringBuilder(80)
    z.append("\r[")
    z.append(b.substring(0,p))
    if (p<50) {z.append(">"); z.append(s.substring(p))}
    z.append("]   ")
    if (p<5) z.append(" ")
    if (p<50) z.append(" ")
    z.append(percent)
    z.append("%   ")
    z.mkString
  }


  private def addProgressListener(request:AmazonWebServiceRequest { // structural
  def setProgressListener(progressListener:ProgressListener):Unit
  }, fileSize:Long, key:String) = request.setProgressListener(new ProgressListener() {
    var uploadedBytes=0L
    val fileName={
      val area=30
      val n=new File(key).getName()
      val l=n.length()
      if (l>area-3)
        "..."+n.substring(l-area+3)
      else
        n
    }
    def progressChanged(progressEvent:ProgressEvent) {
      if(progressEvent.getEventCode() == ProgressEvent.PART_COMPLETED_EVENT_CODE) {
        uploadedBytes=uploadedBytes+progressEvent.getBytesTransfered()
      }
      print(doProgressBar(if (fileSize>0) ((uploadedBytes*100)/fileSize).toInt else 100))
      print(fileName)
      if (progressEvent.getEventCode() == ProgressEvent.COMPLETED_EVENT_CODE)
        println()
    }
  })


  override def requires = HelloPlugin

  /**
   * ObjectMetadata for files we want to be steamed as gzip but inflate in the browser back to their normal Content-type
   * S3's ObjectMatadata is a Java class. it is easiest to modify it with mutation.
    * Here we instantiate and modify an ObjectMetadata but then assign the result to an immutable val.
   */



  /*
   * from a starting dir get all the files and generate a mapping from file to deploy relative path,
   *  if the file is one we can compress then gzip it to a temp dir and use that in the mapping
    * also generate a metadata map for all files that we can compress
   */



}

object S3Publish {
  import java.util.{List => JList}
  import au.com.ecetera.util.Predicate._
  import com.amazonaws.services.simpledb.model.Item
  import spray.http.MediaTypes._
  import com.amazonaws.services.s3.model.{S3ObjectSummary, DeleteObjectsResult, ObjectMetadata}
  import com.amazonaws.{ClientConfiguration, Protocol}
  import com.amazonaws.auth.BasicAWSCredentials
  import com.amazonaws.services.s3.AmazonS3Client

  import scala.util.Try

  private def getClient(creds:Seq[Credentials],host:String) = {
    val cred = Credentials.forHost(creds, host) match {
      case Some(cred) => cred
      case None       => sys.error("Could not find S3 credentials for the host: "+host)
    }
    // username -> Access Key Id ; passwd -> Secret Access Key
    new AmazonS3Client(new BasicAWSCredentials(cred.userName, cred.passwd),
      new ClientConfiguration().withProtocol(Protocol.HTTPS))
  }

  private def getBucket(host:String) = host.stripSuffix(".s3.amazonaws.com")

  def apply[Item](creds: Seq[Credentials],
            items:Seq[Item],
            bucketName: String,
            prefix: S3Prefix,
            progress: Boolean,
            streams: Keys.TaskStreams,
            metadata: MetadataMap,
            op: (AmazonS3Client, S3Bucket, S3Prefix, Item, MetadataMap, Boolean) => Unit,
            msg:(S3Bucket,Item)=>String, lastMsg:(S3Bucket,Seq[Item])=>String,
            lastUploadFile:File): Unit = {

    val client = getClient(creds, bucketName)
    val bucket = getBucket(bucketName)
    items foreach { item =>
      streams.log.debug(msg(bucket,item))
      op(client,bucket, prefix,item, metadata, progress)
    }
    IO.write(lastUploadFile, "" + new java.util.Date().getTime())
    streams.log.info(lastMsg(bucket,items))
  }

  def deleteAll(creds: Seq[Credentials], bucketName: String, prefix:S3Prefix, streams: Keys.TaskStreams,
                op: (AmazonS3Client, S3Bucket, S3Prefix)=>Try[DeleteObjectsResult] ,
                msg:(S3Bucket, String)=>String,
                lastMsg:(S3Bucket)=>String ): Try[DeleteObjectsResult] = {
    val client = getClient(creds, bucketName)
    val bucket = getBucket(bucketName)
    val ret = op(client, bucket, prefix)
    ret.foreach{
      import scala.collection.JavaConverters._
      dor => dor.getDeletedObjects.asScala.foreach( thedo => msg(bucket, thedo.getKey) )
    }
    streams.log.info(lastMsg(bucket))
    ret
  }

  def deleteFromS3(creds: Seq[Credentials], bucketName: String, keys:Try[Set[S3Key]], streams: Keys.TaskStreams,
                op: (AmazonS3Client, S3Bucket, Set[S3Key])=>Try[DeleteObjectsResult] ,
                msg:(S3Bucket, String)=>String,
                lastMsg:(S3Bucket)=>String ): Try[DeleteObjectsResult] = {
    val client = getClient(creds, bucketName)
    val bucket = getBucket(bucketName)
    val ret = keys flatMap {ks => op(client, bucket, ks)}
    ret.foreach{
      import scala.collection.JavaConverters._
      dor => dor.getDeletedObjects.asScala.foreach( thedo => msg(bucket, thedo.getKey) )
    }
    streams.log.info(lastMsg(bucket))
    ret
  }

  /** Returns a Sequence of keys (S3 paths) that no longer exist locally.
    * The Idea is that we use this list to delete those files remotely before incrementally uploading local changes.
    * This will produce an rsync like behaviour where the S3 bucket reflects the local staging Dir.
   *
   * @param creds
   * @param bucketName
   * @return S3 keys that exist remotely but don't exist locally
   */
  def diffWithLocal(assetDir: File, creds: Seq[Credentials], bucketName: String, prefix: S3Prefix, op: (AmazonS3Client, S3Bucket, S3Prefix) => Try[List[S3ObjectSummary]]): Try[Set[S3Key]] = {
    import scala.collection.JavaConverters._
    val client = getClient(creds, bucketName)
    val bucket = getBucket(bucketName)
    op(client, bucket, prefix) map { s3s =>
      val remotes = s3s.map(os => if(prefix.isEmpty) { os.getKey} else { os.getKey.stripPrefix(prefix)})
      val assets = (assetDir ** "*").get.filter( ((d:File) => d != assetDir ) && ((d:File) => !d.isDirectory))
      val assetsS = (assets map { IO.relativize(assetDir, _)} flatten) toSet
      val ret = remotes.toSet.diff(assetsS)
      ret
    }
  }


  def calculateFilesAndMetadata(assetDir: File, lastUploadFile:File, modifiedDir: File, compress: Boolean, dontMod: Seq[File], incremental: Boolean/*, log: Logger*/): (Seq[(File, String)], MetadataMap) = {
    IO.assertDirectory(assetDir)
    //get list of all files under assetDir that need to be uploaded ie only new ones if incremental
    val assets = (assetDir ** "*").get.filter( ((d:File) => d != assetDir ) && ((d:File) => !d.isDirectory) && newfiles(incremental, lastUploadFile))
// d => { evalx(d) && evaly(d) )

    val compressedFIles: Map[String, (File, MediaType)] = compress match {
        case false => Map()
        case true  => {
          val compressed =  {assets.filter(canMod(dontMod.toSet)) map ((f) =>
            for {
              ext <- extractFileNameExtention(f.getName)
              mediaType <- forExtension(ext) if mediaType.compressible
              relPath <- IO.relativize(assetDir, f)

            } yield (relPath, (gzipFileIfCompressibleMediaType(f, relPath, modifiedDir, mediaType: MediaType/*, log */), mediaType) ))
          }.flatten
          compressed.toMap
        }
      }

    val mappings: Seq[(File, String)] = for{
        f <- assets
        relPath <- IO.relativize(assetDir, f)
      } yield if(!compressedFIles.contains(relPath)) (f, relPath) else (compressedFIles(relPath)._1, relPath)

    val metadataMap: MetadataMap = {compressedFIles map  { case (relpath, (gzip, meditype)) => {
      for{
        (mt, omd) <- gzipMetaData(meditype)
      } yield ((relpath, omd) )

    }
    }}.flatten.toMap

    (mappings, metadataMap)

  }

  /** File Filter predicate, only allows files not in dontMod through **/
  def canMod(dontModSet: Set[File]): (File) => Boolean = (f) => {
    //TODO improove this algo so we only traverse lists once
    !dontModSet.contains(f)
  }
  def newfiles(incremental: Boolean, lastUpload:File): (File) => Boolean = (f) => {
    if (incremental) modified(f, lastUpload) else true
  }

  def modified(f: File, lastRunFile: File): Boolean = f.lastModified() > lastRunTime(lastRunFile)

  def lastRunTime(lastRunFile: File) = {
    try {
      IO.read(lastRunFile).trim().toLong
    } catch {
      case _: Exception => 0L
    }
  }

  val fnamePat = """(.*)[.]([^.]*)""".r

  /** associate a precanned S3 ObjectMetadata with target file name **/
  def assignMetadata(s3Key: String, mdMap: MetadataMap): Option[(String, ObjectMetadata)] = {
    for {
      extension <- extractFileNameExtention(s3Key)
      md <- mdMap.get(extension)
    } yield (s3Key, md)
  }

  def extractFileNameExtention(s3Key: String) = s3Key match {
    case fnamePat(fn,ext) => if(!ext.isEmpty) Some(ext) else None
    case _ => None
  }

  /**
   * Create an ObjectMetadata with gzip encoding for a MediaType if appropriate
   * @param mediaType
   * @return
   */
  def gzipMetaData(mediaType: MediaType): Option[(MediaType,ObjectMetadata)] = {
    import au.com.ecetera.http.ContentCoding._
    if(mediaType.compressible) {
      var omd = new ObjectMetadata()
      omd.setContentType(mediaType.toString)
      omd.setContentEncoding(`gzip`)
      Some((mediaType, omd))
    } else None
  }

  /**
   * gzip a file to the output dir in same relative position as the input file is to its basedir.
   * @param input
   * @param outputDir
   * @return Option[output: File]
   */
  def gzipFile(input: File, relPath: String, outputDir: File/*, log: Logger */): File = {
      IO.createDirectory(outputDir)
      val output = outputDir / relPath
//      log.info(s"zipping ${input.getAbsolutePath} => ${output.getAbsolutePath}")
          println(s"zipping ${input.getAbsolutePath} => ${output.getAbsolutePath}")
      IO.gzip(input, output)
      output
  }

  def gzipFileIfCompressibleMediaType(input: File, relPath: String, outputDir: File, mediaType: MediaType/*, log: Logger */): File = {
    if(mediaType.compressible) {
      gzipFile(input, relPath, outputDir/*, log */)
    } else input
  }


}
