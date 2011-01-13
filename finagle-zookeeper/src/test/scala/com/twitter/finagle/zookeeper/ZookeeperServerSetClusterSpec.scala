package com.twitter.finagle.zookeeper

import org.specs.Specification
import org.apache.zookeeper.server.{NIOServerCnxn, ZooKeeperServer}
import com.twitter.common.quantity._
import com.twitter.common.io.FileUtils.createTempDir
import org.apache.zookeeper.server.persistence.FileTxnSnapLog
import com.twitter.common.zookeeper.{ServerSetImpl, ZooKeeperClient}
import com.twitter.finagle.builder.{Codec, ClientBuilder, ServerBuilder}
import com.twitter.finagle.Service
import org.jboss.netty.handler.codec.string.{StringEncoder, StringDecoder}
import org.jboss.netty.util.CharsetUtil
import org.jboss.netty.handler.codec.frame.{Delimiters, DelimiterBasedFrameDecoder}
import com.twitter.util.{Future, RandomSocket}
import com.twitter.conversions.time._
import org.jboss.netty.channel._

class StringCodec extends Codec[String, String] {
  val serverPipelineFactory = new ChannelPipelineFactory {
    def getPipeline = {
      val pipeline = Channels.pipeline()
      pipeline.addLast("line",
        new DelimiterBasedFrameDecoder(100, Delimiters.lineDelimiter: _*))
      pipeline.addLast("stringDecoder", new StringDecoder(CharsetUtil.UTF_8))
      pipeline.addLast("stringEncoder", new StringEncoder(CharsetUtil.UTF_8))
      pipeline
    }
  }

  val clientPipelineFactory = new ChannelPipelineFactory {
    def getPipeline = {
      val pipeline = Channels.pipeline()
      pipeline.addLast("stringEncode", new StringEncoder(CharsetUtil.UTF_8))
      pipeline.addLast("stringDecode", new StringDecoder(CharsetUtil.UTF_8))
      pipeline
    }
  }
}

object ZookeeperServerSetClusterSpec extends Specification {
  "ZookeeperServerSetCluster" should {
    val zookeeperAddress = RandomSocket.nextAddress
    val serviceAddress = RandomSocket.nextAddress
    var connectionFactory: NIOServerCnxn.Factory = null
    var zookeeperServer: ZooKeeperServer = null
    var zookeeperClient: ZooKeeperClient = null

    doBefore {
      zookeeperServer = new ZooKeeperServer(
        new FileTxnSnapLog(createTempDir(), createTempDir()),
        new ZooKeeperServer.BasicDataTreeBuilder)
      connectionFactory = new NIOServerCnxn.Factory(zookeeperAddress)
      connectionFactory.startup(zookeeperServer)
      zookeeperClient = new ZooKeeperClient(
        Amount.of(100, Time.MILLISECONDS),
        zookeeperAddress)
    }

    doAfter {
      connectionFactory.shutdown()
      zookeeperClient.close()
    }

    "register the server with ZooKeeper" in {
      val serverSet = new ServerSetImpl(zookeeperClient, "/twitter/services/silly")
      val cluster = new ZookeeperServerSetCluster(serverSet)

      val sillyService = new Service[String, String] {
        def apply(request: String) = {
          println("hair")
          Future(request.reverse)
        }
      }
      val server = ServerBuilder()
        .codec(new StringCodec)
        .service(sillyService)
        .bindTo(serviceAddress)
        .build()

      cluster.join(serviceAddress)

      val client = ClientBuilder()
        .cluster(cluster)
        .codec(new StringCodec)
        .build()

      client("hello\n")(1.seconds) mustEqual "olleh"
    }
  }
}