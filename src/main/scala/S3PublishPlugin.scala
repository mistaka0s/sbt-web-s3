package au.com.ecetera.sbt


import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ProgressEvent, ProgressListener, PutObjectRequest}
import sbt._
import Keys._
import spray.http.MediaType



object S3PublishPlugin extends sbt.AutoPlugin {


  // This object namespaces all our settings
  object S3P {

    lazy val s3PublishuploadData = TaskKey[(Seq[(File, String)], MetadataMap)]("all S3upload data")

    lazy val publishToS3 = taskKey[Unit]("Publish Web assets to S3")

    lazy val s3PublishIncremental = settingKey[Boolean]("Boolean, if true only publishes the files that have changed since last time publishToS3 was run. Defaults to false")

    lazy val s3PublishCompress = settingKey[Boolean]("Boolean, if true we compress eligible files. Defaults to true")

    lazy val s3PublishDontModify = settingKey[Seq[File]]("Sequence of Files that we don't want to modify on upload, ie files that would normally be compressed but we don't want them to be.")

    lazy val s3PublishAssetDir = settingKey[File]("Base dir for Web assets to publish. \nDefaults to the sbt-web stagingDirectory: target.value/web/stage\n note that it doesn't not depend on the sbt-web plugin but is intended to work with it.")

//    lazy val metadataMapSeq = settingKey[Seq[(String, ObjectMetadata)]]("Seq of file extension to S3 ObjectMetadata")
//
//    lazy val metadataMap = taskKey[MetadataMap]("Map build out of metadataMapSeq")

    /**
     * A string representing the S3 bucket name, in one of two forms:
     *  1. "mybucket.s3.amazonaws.com", where "mybucket" is the bucket name, or
     *  1. "mybucket", for instance in case the name is a fully qualified hostname used in a CNAME
     */
    lazy val s3PublishHost=settingKey[String]("Host used by the publishToS3 operation, either \"mybucket.s3.amazonaws.com\" or \"mybucket\".")

    /**
     * A list of S3 keys (pathnames) representing objects in a bucket on which a certain operation should be performed.
     */
//    val keys=TaskKey[Seq[String]]("s3-keys","List of S3 keys (pathnames) on which to perform a certain operation.")

    /**
     * If you set "progress" to true, a progress indicator will be displayed while the individual files are uploaded or downloaded.
     * Only recommended for interactive use or testing; the default value is false.
     */
    val s3PublishProgress=settingKey[Boolean]("Set to true to get a progress indicator during S3 uploads/downloads (default false).")

  }

  import S3P._


  override def projectSettings = Seq (
    s3PublishCompress    := true,
    s3PublishIncremental := false,
    s3PublishDontModify  := Seq(),
    s3PublishAssetDir    := target.value / "web" / "stage",
    s3PublishuploadData  := S3Publish.calculateFilesAndMetadata(s3PublishAssetDir.value, target.value / "modfiles", s3PublishCompress.value, s3PublishDontModify.value, s3PublishIncremental.value /*, streams.value.log */ ),
//    com.typesafe.sbt.S3Plugin.S3.metadata := s3PublishuploadData.value._2,
//    (mappings in com.typesafe.sbt.S3Plugin.S3.upload) := s3PublishuploadData.value._1,
    publishToS3          <<= initUpload(publishToS3,
      { case (client,bucket,(file,key),metadataMap,progress) =>
        val mdo = metadataMap.get(key)
        val request=new PutObjectRequest(bucket,key,file)
        if (progress) addProgressListener(request,file.length(),key)
        if (mdo.isDefined) {
          client.putObject(request.withMetadata(mdo.get))
        } else client.putObject(request)
      },
      { case (bucket,(file,key)) =>  "Uploading "+file.getAbsolutePath()+" as "+key+" into "+bucket },
      {      (bucket,mapps) =>       "Uploaded "+mapps.length+" files to the S3 bucket \""+bucket+"\"." }),



    s3PublishHost := "",
    s3PublishProgress := false
  )
//      (s3PublishuploadData in publishToS3, host in publishToS3, streams) map { (updata, host, s) =>
//        streams.log.info(lastMsg(bucket,items))
//      }

  def initUpload(thisTask:TaskKey[Unit],
                       op: (AmazonS3Client, Bucket, (File,String), MetadataMap, Boolean) => Unit,
                       msg:(Bucket,(File,String))=>String, lastMsg:(Bucket,Seq[(File,String)])=>String ) =
    (s3PublishuploadData in thisTask, credentials in thisTask, s3PublishHost in thisTask, s3PublishProgress in thisTask, streams ) map {
      (uploaddata, creds, host, progress, streams) =>
        S3Publish(creds,uploaddata._1,host,progress,streams,uploaddata._2, op,msg, lastMsg)
  }


  private def progressBar(percent:Int) = {
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
      print(progressBar(if (fileSize>0) ((uploadedBytes*100)/fileSize).toInt else 100))
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
  import au.com.ecetera.util.Predicate._
  import spray.http.MediaTypes._
  import com.amazonaws.services.s3.model.{ObjectMetadata}
  import com.amazonaws.{ClientConfiguration, Protocol}
  import com.amazonaws.auth.BasicAWSCredentials
  import com.amazonaws.services.s3.AmazonS3Client

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
            host: String,
            progress: Boolean,
            streams: Keys.TaskStreams,
            metadata: MetadataMap,
            op: (AmazonS3Client, Bucket, Item, MetadataMap, Boolean) => Unit,
            msg:(Bucket,Item)=>String, lastMsg:(Bucket,Seq[Item])=>String ): Unit = {

    val client = getClient(creds, host)
    val bucket = getBucket(host)
    items foreach { item =>
      streams.log.debug(msg(bucket,item))
      op(client,bucket,item, metadata, progress)
    }
    streams.log.info(lastMsg(bucket,items))
  }



  def calculateFilesAndMetadata(assetDir: File, modifiedDir: File, compress: Boolean, dontMod: Seq[File], incremental: Boolean/*, log: Logger*/): (Seq[(File, String)], MetadataMap) = {
    IO.assertDirectory(assetDir)
    //get list of all files under assetDir that need to be uploaded ie only new ones if incremental
    val assets = (assetDir ** "*").get.filter( ((d:File) => d != assetDir ) && ((d:File) => !d.isDirectory) && newfiles(incremental))
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
  def newfiles(incremental: Boolean): (File) => Boolean = (f) => {
    if (incremental) (f.lastModified > lastRunTime) else true
  }

  def lastRunTime = 0L // TODO

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
