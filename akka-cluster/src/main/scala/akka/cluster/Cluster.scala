/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import org.apache.zookeeper._
import org.apache.zookeeper.Watcher.Event._
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.recipes.lock.{ WriteLock, LockListener }

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._

import java.util.{ List ⇒ JList }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.net.InetSocketAddress
import javax.management.StandardMBean

import scala.collection.mutable.ConcurrentMap
import scala.collection.JavaConversions._

import akka.util._
import Helpers._

import akka.actor._
import Actor._
import Status._
import DeploymentConfig._

import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future }
import akka.remoteinterface._
import akka.routing.RouterType

import akka.config.{ Config, Supervision }
import Supervision._
import Config._

import akka.serialization.{ Serialization, Serializer, ActorSerialization }
import ActorSerialization._
import akka.serialization.Compression.LZF

import akka.cluster.zookeeper._
import ChangeListener._
import ClusterProtocol._
import RemoteDaemonMessageType._

import com.eaio.uuid.UUID

import com.google.protobuf.ByteString
import java.util.concurrent.{ CopyOnWriteArrayList, Callable, ConcurrentHashMap }

// FIXME add watch for each node that when the entry for the node is removed then the node shuts itself down

/**
 * JMX MBean for the cluster service.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ClusterNodeMBean {

  def start()

  def stop()

  def disconnect()

  def reconnect()

  def resign()

  def isConnected: Boolean

  def getRemoteServerHostname: String

  def getRemoteServerPort: Int

  def getNodeName: String

  def getClusterName: String

  def getZooKeeperServerAddresses: String

  def getMemberNodes: Array[String]

  def getNodeAddres(): NodeAddress

  def getLeaderLockName: String

  def isLeader: Boolean

  def getUuidsForClusteredActors: Array[String]

  def getAddressesForClusteredActors: Array[String]

  def getUuidsForActorsInUse: Array[String]

  def getAddressesForActorsInUse: Array[String]

  def getNodesForActorInUseWithAddress(address: String): Array[String]

  def getUuidsForActorsInUseOnNode(nodeName: String): Array[String]

  def getAddressesForActorsInUseOnNode(nodeName: String): Array[String]

  def setConfigElement(key: String, value: String)

  def getConfigElement(key: String): AnyRef

  def removeConfigElement(key: String)

  def getConfigElementKeys: Array[String]

  def getMemberShipPathFor(node: String): String

  def getConfigurationPathFor(key: String): String

  def getActorAddresstoNodesPathFor(actorAddress: String): String

  def getActorAddressToNodesPathForWithNodeName(actorAddress: String, nodeName: String): String

  def getNodeToUuidsPathFor(node: String): String

  def getNodeToUuidsPathFor(node: String, uuid: UUID): String

  def getActorAddressRegistryPathFor(actorAddress: String): String

  def getActorAddressRegistrySerializerPathFor(actorAddress: String): String

  def getActorAddressRegistryUuidPathFor(actorAddress: String): String

  def getActorUuidRegistryNodePathFor(uuid: UUID): String

  def getActorUuidRegistryRemoteAddressPathFor(uuid: UUID): String

  def getActorAddressToUuidsPathFor(actorAddress: String): String

  def getActorAddressToUuidsPathForWithNodeName(actorAddress: String, uuid: UUID): String
}

/**
 * Module for the Cluster. Also holds global state such as configuration data etc.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Cluster {
  val EMPTY_STRING = "".intern

  // config options
  val name = Config.clusterName
  val zooKeeperServers = config.getString("akka.cluster.zookeeper-server-addresses", "localhost:2181")
  val remoteServerPort = config.getInt("akka.cluster.remote-server-port", 2552)
  val sessionTimeout = Duration(config.getInt("akka.cluster.session-timeout", 60), TIME_UNIT).toMillis.toInt
  val connectionTimeout = Duration(config.getInt("akka.cluster.connection-timeout", 60), TIME_UNIT).toMillis.toInt
  val maxTimeToWaitUntilConnected = Duration(config.getInt("akka.cluster.max-time-to-wait-until-connected", 30), TIME_UNIT).toMillis.toInt
  val shouldCompressData = config.getBool("akka.cluster.use-compression", false)
  val enableJMX = config.getBool("akka.enable-jmx", true)
  val remoteDaemonAckTimeout = Duration(config.getInt("akka.cluster.remote-daemon-ack-timeout", 30), TIME_UNIT).toMillis.toInt
  val includeRefNodeInReplicaSet = config.getBool("akka.cluster.include-ref-node-in-replica-set", true)
  val clusterDirectory = config.getString("akka.cluster.log-directory", "_akka_cluster")

  val clusterDataDirectory = clusterDirectory + "/data"
  val clusterLogDirectory = clusterDirectory + "/log"

  @volatile
  private var properties = Map.empty[String, String]

  /**
   * Use to override JVM options such as <code>-Dakka.cluster.nodename=node1</code> etc.
   * Currently supported options are:
   * <pre>
   *   Cluster setProperty ("akka.cluster.nodename", "node1")
   *   Cluster setProperty ("akka.cluster.hostname", "darkstar.lan")
   *   Cluster setProperty ("akka.cluster.port", "1234")
   * </pre>
   */
  def setProperty(property: (String, String)) {
    properties = properties + property
  }

  private def nodename: String = properties.get("akka.cluster.nodename") match {
    case Some(uberride) ⇒ uberride
    case None           ⇒ Config.nodename
  }

  private def hostname: String = properties.get("akka.cluster.hostname") match {
    case Some(uberride) ⇒ uberride
    case None           ⇒ Config.hostname
  }

  private def port: Int = properties.get("akka.cluster.port") match {
    case Some(uberride) ⇒ uberride.toInt
    case None           ⇒ Config.remoteServerPort
  }

  val defaultZooKeeperSerializer = new SerializableSerializer

  private val _zkServer = new AtomicReference[Option[ZkServer]](None)

  /**
   * The node address.
   */
  val nodeAddress = NodeAddress(name, nodename)

  /**
   * The reference to the running ClusterNode.
   */
  val node = {
    if (nodeAddress eq null) throw new IllegalArgumentException("NodeAddress can't be null")
    new DefaultClusterNode(nodeAddress, hostname, port, zooKeeperServers, defaultZooKeeperSerializer)
  }

  /**
   * Looks up the local hostname.
   */
  def lookupLocalhostName = NetworkUtil.getLocalhostName

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(): ZkServer =
    startLocalCluster(clusterDataDirectory, clusterLogDirectory, 2181, 5000)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(port: Int, tickTime: Int): ZkServer =
    startLocalCluster(clusterDataDirectory, clusterLogDirectory, port, tickTime)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(tickTime: Int): ZkServer =
    startLocalCluster(clusterDataDirectory, clusterLogDirectory, 2181, tickTime)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(dataPath: String, logPath: String): ZkServer =
    startLocalCluster(dataPath, logPath, 2181, 500)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(dataPath: String, logPath: String, port: Int, tickTime: Int): ZkServer = {
    try {
      val zkServer = AkkaZooKeeper.startLocalServer(dataPath, logPath, port, tickTime)
      _zkServer.set(Some(zkServer))
      zkServer
    } catch {
      case e: Throwable ⇒
        EventHandler.error(e, this, "Could not start local ZooKeeper cluster")
        throw e
    }
  }

  /**
   * Shut down the local ZooKeeper server.
   */
  def shutdownLocalCluster() {
    withPrintStackTraceOnError {
      EventHandler.info(this, "Shuts down local cluster")
      _zkServer.get.foreach(_.shutdown())
      _zkServer.set(None)
    }
  }

  /**
   * Creates a new AkkaZkClient.
   */
  def newZkClient(): AkkaZkClient = new AkkaZkClient(zooKeeperServers, sessionTimeout, connectionTimeout, defaultZooKeeperSerializer)

  def createQueue(rootPath: String, blocking: Boolean = true) = new ZooKeeperQueue(node.zkClient, rootPath, blocking)

  def barrier(name: String, count: Int): ZooKeeperBarrier =
    ZooKeeperBarrier(node.zkClient, node.nodeAddress.clusterName, name, node.nodeAddress.nodeName, count)

  def barrier(name: String, count: Int, timeout: Duration): ZooKeeperBarrier =
    ZooKeeperBarrier(node.zkClient, node.nodeAddress.clusterName, name, node.nodeAddress.nodeName, count, timeout)

  def uuidToString(uuid: UUID): String = uuid.toString

  def stringToUuid(uuid: String): UUID = {
    if (uuid eq null) throw new ClusterException("UUID is null")
    if (uuid == "") throw new ClusterException("UUID is an empty string")
    try {
      new UUID(uuid)
    } catch {
      case e: StringIndexOutOfBoundsException ⇒
        val error = new ClusterException("UUID not valid [" + uuid + "]")
        EventHandler.error(error, this, "")
        throw error
    }
  }

  def uuidProtocolToUuid(uuid: UuidProtocol): UUID = new UUID(uuid.getHigh, uuid.getLow)

  def uuidToUuidProtocol(uuid: UUID): UuidProtocol =
    UuidProtocol.newBuilder
      .setHigh(uuid.getTime)
      .setLow(uuid.getClockSeqAndNode)
      .build
}

/**
 * A Cluster is made up by a bunch of jvm's, the ClusterNode.
 *
 * These are the path tree holding the cluster meta-data in ZooKeeper.
 *
 * Syntax: foo means a variable string, 'foo' means a symbol that does not change and "data" in foo[data] means the value (in bytes) for the node "foo"
 *
 * <pre>
 *   /clusterName/'members'/nodeName
 *   /clusterName/'config'/key[bytes]
 *
 *   /clusterName/'actor-address-to-nodes'/actorAddress/nodeName
 *   /clusterName/'actors-node-to-uuids'/nodeName/actorUuid
 *
 *   /clusterName/'actor-address-registry'/actorAddress/'serializer'[serializerName]
 *   /clusterName/'actor-address-registry'/actorAddress/'uuid'[actorUuid]
 *
 *   /clusterName/'actor-uuid-registry'/actorUuid/'node'[nodeName]
 *   /clusterName/'actor-uuid-registry'/actorUuid/'node'/ip:port
 *   /clusterName/'actor-uuid-registry'/actorUuid/'address'[actorAddress]
 *
 *   /clusterName/'actor-address-to-uuids'/actorAddress/actorUuid
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DefaultClusterNode private[akka] (
  val nodeAddress: NodeAddress,
  val hostname: String = Config.hostname,
  val port: Int = Config.remoteServerPort,
  val zkServerAddresses: String,
  val serializer: ZkSerializer) extends ErrorHandler with ClusterNode {
  self ⇒

  if ((hostname eq null) || hostname == "") throw new NullPointerException("Host name must not be null or empty string")
  if (port < 1) throw new NullPointerException("Port can not be negative")
  if (nodeAddress eq null) throw new IllegalArgumentException("'nodeAddress' can not be 'null'")

  val clusterJmxObjectName = JMX.nameFor(hostname, "monitoring", "cluster")

  import Cluster._

  private[cluster] lazy val remoteClientLifeCycleListener = localActorOf(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) ⇒ client.shutdownClientModule()
      case RemoteClientDisconnected(client, address) ⇒ client.shutdownClientModule()
      case _                                         ⇒ //ignore other
    }
  }, "akka.cluster.RemoteClientLifeCycleListener").start()

  private[cluster] lazy val remoteDaemon = localActorOf(new RemoteClusterDaemon(this), RemoteClusterDaemon.Address).start()

  private[cluster] lazy val remoteDaemonSupervisor = Supervisor(
    SupervisorConfig(
      OneForOneStrategy(List(classOf[Exception]), Int.MaxValue, Int.MaxValue), // is infinite restart what we want?
      Supervise(
        remoteDaemon,
        Permanent)
        :: Nil))

  lazy val remoteService: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport
    remote.start(hostname, port)
    remote.register(RemoteClusterDaemon.Address, remoteDaemon)
    remote.addListener(remoteClientLifeCycleListener)
    remote
  }

  lazy val remoteServerAddress: InetSocketAddress = remoteService.address

  val isConnected = new Switch(false)

  // static nodes
  val CLUSTER_PATH = "/" + nodeAddress.clusterName
  val MEMBERSHIP_PATH = CLUSTER_PATH + "/members"
  val CONFIGURATION_PATH = CLUSTER_PATH + "/config"
  val PROVISIONING_PATH = CLUSTER_PATH + "/provisioning"
  val ACTOR_ADDRESS_NODES_TO_PATH = CLUSTER_PATH + "/actor-address-to-nodes"
  val ACTOR_ADDRESS_REGISTRY_PATH = CLUSTER_PATH + "/actor-address-registry"
  val ACTOR_UUID_REGISTRY_PATH = CLUSTER_PATH + "/actor-uuid-registry"
  val ACTOR_ADDRESS_TO_UUIDS_PATH = CLUSTER_PATH + "/actor-address-to-uuids"
  val NODE_TO_ACTOR_UUIDS_PATH = CLUSTER_PATH + "/node-to-actors-uuids"

  val basePaths = List(
    CLUSTER_PATH,
    MEMBERSHIP_PATH,
    ACTOR_ADDRESS_REGISTRY_PATH,
    ACTOR_UUID_REGISTRY_PATH,
    ACTOR_ADDRESS_NODES_TO_PATH,
    NODE_TO_ACTOR_UUIDS_PATH,
    ACTOR_ADDRESS_TO_UUIDS_PATH,
    CONFIGURATION_PATH,
    PROVISIONING_PATH)

  val LEADER_ELECTION_PATH = CLUSTER_PATH + "/leader" // should NOT be part of 'basePaths' only used by 'leaderLock'

  private val membershipNodePath = membershipPathFor(nodeAddress.nodeName)

  def membershipNodes: Array[String] = locallyCachedMembershipNodes.toList.toArray.asInstanceOf[Array[String]]

  private[akka] val nodeConnections: ConcurrentMap[String, Tuple2[InetSocketAddress, ActorRef]] = {
    val conns = new ConcurrentHashMap[String, Tuple2[InetSocketAddress, ActorRef]]
    if (includeRefNodeInReplicaSet)
      conns.put(nodeAddress.nodeName, (remoteServerAddress, remoteDaemon)) // add the remote connection to 'this' node as well, but as a 'local' actor
    conns
  }

  // zookeeper listeners
  private val stateListener = new StateListener(this)
  private val membershipListener = new MembershipChildListener(this)

  // cluster node listeners
  private val changeListeners = new CopyOnWriteArrayList[ChangeListener]()

  // Address -> ClusterActorRef
  private val clusterActorRefs = new Index[InetSocketAddress, ClusterActorRef]

  // ZooKeeper client
  lazy private[cluster] val zkClient = new AkkaZkClient(zkServerAddresses, sessionTimeout, connectionTimeout, serializer)

  // leader election listener, registered to the 'leaderLock' below
  lazy private[cluster] val leaderElectionCallback = new LockListener {
    override def lockAcquired() {
      EventHandler.info(this, "Node [%s] is the new leader".format(self.nodeAddress.nodeName))
      self.publish(NewLeader(self.nodeAddress.nodeName))
    }

    override def lockReleased() {
      EventHandler.info(this, "Node [%s] is *NOT* the leader anymore".format(self.nodeAddress.nodeName))
    }
  }

  // leader election lock in ZooKeeper
  lazy private[cluster] val leaderLock = new WriteLock(
    zkClient.connection.getZookeeper,
    LEADER_ELECTION_PATH, null,
    leaderElectionCallback)

  if (enableJMX) createMBean

  // =======================================
  // Node
  // =======================================

  def isRunning: Boolean = isConnected.isOn

  def start(): ClusterNode = {
    isConnected.switchOn {
      initializeNode()
    }

    this
  }

  private[cluster] def initializeNode() {
    EventHandler.info(this,
      ("\nCreating cluster node with" +
        "\n\tcluster name = [%s]" +
        "\n\tnode name = [%s]" +
        "\n\tport = [%s]" +
        "\n\tzookeeper server addresses = [%s]" +
        "\n\tserializer = [%s]")
        .format(nodeAddress.clusterName, nodeAddress.nodeName, port, zkServerAddresses, serializer))
    EventHandler.info(this, "Starting up remote server [%s]".format(remoteServerAddress.toString))
    createZooKeeperPathStructureIfNeeded()
    registerListeners()
    joinCluster()
    joinLeaderElection()
    fetchMembershipNodes()
    EventHandler.info(this, "Cluster node [%s] started successfully".format(nodeAddress))
  }

  def shutdown() {
    def shutdownNode() {
      ignore[ZkNoNodeException](zkClient.deleteRecursive(membershipNodePath))

      locallyCachedMembershipNodes.clear()

      nodeConnections.toList.foreach({
        case (_, (address, _)) ⇒
          Actor.remote.shutdownClientConnection(address) // shut down client connections
      })

      remoteService.shutdown() // shutdown server

      remoteClientLifeCycleListener.stop()
      remoteDaemon.stop()

      // for monitoring remote listener
      registry.local.actors.filter(remoteService.hasListener).foreach(_.stop())

      nodeConnections.clear()

      disconnect()
      EventHandler.info(this, "Cluster node shut down [%s]".format(nodeAddress))
    }

    isConnected.switchOff {
      shutdownNode()
    }
  }

  def disconnect(): ClusterNode = {
    zkClient.unsubscribeAll()
    zkClient.close()
    this
  }

  def reconnect(): ClusterNode = {
    zkClient.reconnect()
    this
  }

  // =======================================
  // Change notification
  // =======================================

  /**
   * Registers a cluster change listener.
   */
  def register(listener: ChangeListener): ClusterNode = {
    changeListeners.add(listener)
    this
  }

  private[cluster] def publish(change: ChangeNotification) {
    changeListeners.iterator.foreach(_.notify(change, this))
  }

  // =======================================
  // Leader
  // =======================================

  /**
   * Returns the name of the current leader lock.
   */
  def leader: String = leaderLock.getId

  /**
   * Returns true if 'this' node is the current leader.
   */
  def isLeader: Boolean = leaderLock.isOwner

  /**
   * Explicitly resign from being a leader. If this node is not a leader then this operation is a no-op.
   */
  def resign() {
    if (isLeader) leaderLock.unlock()
  }

  // =======================================
  // Actor
  // =======================================

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, 0, Transient, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, 0, replicationScheme, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationFactor: Int, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, replicationFactor, Transient, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationFactor: Int, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, replicationFactor, replicationScheme, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, 0, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, 0, replicationScheme, serializeMailbox, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationFactor: Int, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, replicationFactor, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, () ⇒ Actor.actorOf(actorClass, actorAddress).start, replicationFactor, replicationScheme, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, 0, Transient, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, 0, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, 0, replicationScheme, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationFactor: Int, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, replicationFactor, Transient, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, replicationFactor, replicationScheme, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationFactor: Int, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, replicationFactor, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorAddress, actorFactory, 0, replicationScheme, serializeMailbox, serializer)

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: AnyRef): ClusterNode =
    store(actorAddress, actorFactory, replicationFactor, replicationScheme, serializeMailbox, serializer.asInstanceOf[Serializer])

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationFactor: Int, serializeMailbox: Boolean, serializer: AnyRef): ClusterNode =
    store(actorAddress, actorFactory, replicationFactor, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(
    actorAddress: String,
    actorFactory: () ⇒ ActorRef,
    replicationFactor: Int,
    replicationScheme: ReplicationScheme,
    serializeMailbox: Boolean,
    serializer: Serializer): ClusterNode = if (isConnected.isOn) {

    EventHandler.debug(this,
      "Storing actor with address [%s] in cluster".format(actorAddress))

    val actorFactoryBytes =
      Serialization.serialize(actorFactory) match {
        case Left(error) ⇒ throw error
        case Right(bytes) ⇒
          if (shouldCompressData) LZF.compress(bytes)
          else bytes
      }

    val actorAddressRegistryPath = actorAddressRegistryPathFor(actorAddress)

    // create ADDRESS -> Array[Byte] for actor registry
    try {
      zkClient.writeData(actorAddressRegistryPath, actorFactoryBytes)
    } catch {
      case e: ZkNoNodeException ⇒ // if not stored yet, store the actor
        zkClient.retryUntilConnected(new Callable[Either[String, Exception]]() {
          def call: Either[String, Exception] = {
            try {
              Left(zkClient.connection.create(actorAddressRegistryPath, actorFactoryBytes, CreateMode.PERSISTENT))
            } catch {
              case e: KeeperException.NodeExistsException ⇒ Right(e)
            }
          }
        }) match {
          case Left(path)       ⇒ path
          case Right(exception) ⇒ actorAddressRegistryPath
        }
    }

    // create ADDRESS -> SERIALIZER CLASS NAME mapping
    try {
      zkClient.createPersistent(actorAddressRegistrySerializerPathFor(actorAddress), serializer.identifier.toString)
    } catch {
      case e: ZkNodeExistsException ⇒ zkClient.writeData(actorAddressRegistrySerializerPathFor(actorAddress), serializer.identifier.toString)
    }

    // create ADDRESS -> NODE mapping
    ignore[ZkNodeExistsException](zkClient.createPersistent(actorAddressToNodesPathFor(actorAddress)))

    // create ADDRESS -> UUIDs mapping
    ignore[ZkNodeExistsException](zkClient.createPersistent(actorAddressToUuidsPathFor(actorAddress)))

    useActorOnNodes(nodesForReplicationFactor(replicationFactor, Some(actorAddress)).toArray, actorAddress)

    this
  } else throw new ClusterException("Not connected to cluster")

  /**
   * Removes actor from the cluster.
   */
  // def remove(actorRef: ActorRef) {
  //   remove(actorRef.address)
  // }

  /**
   * Removes actor with uuid from the cluster.
   */
  // def remove(actorAddress: String) {
  //   releaseActorOnAllNodes(actorAddress)
  //   // warning: ordering matters here
  //   // FIXME remove ADDRESS to UUID mapping?
  //   ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAddressToUuidsPathFor(actorAddress)))
  //   ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAddressRegistryPathFor(actorAddress)))
  //   ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAddressToNodesPathFor(actorAddress)))
  // }

  /**
   * Is the actor with uuid clustered or not?
   */
  def isClustered(actorAddress: String): Boolean = if (isConnected.isOn) {
    zkClient.exists(actorAddressRegistryPathFor(actorAddress))
  } else false

  /**
   * Is the actor with uuid in use on 'this' node or not?
   */
  def isInUseOnNode(actorAddress: String): Boolean = isInUseOnNode(actorAddress, nodeAddress)

  /**
   * Is the actor with uuid in use or not?
   */
  def isInUseOnNode(actorAddress: String, node: NodeAddress): Boolean = if (isConnected.isOn) {
    zkClient.exists(actorAddressToNodesPathFor(actorAddress, node.nodeName))
  } else false

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String): Option[LocalActorRef] = use(actorAddress, serializerForActor(actorAddress))

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String, serializer: Serializer): Option[LocalActorRef] = if (isConnected.isOn) {
    val nodeName = nodeAddress.nodeName

    ignore[ZkNodeExistsException](zkClient.createEphemeral(actorAddressToNodesPathFor(actorAddress, nodeName)))

    val actorFactoryPath = actorAddressRegistryPathFor(actorAddress)
    zkClient.retryUntilConnected(new Callable[Either[Exception, () ⇒ LocalActorRef]]() {
      def call: Either[Exception, () ⇒ LocalActorRef] = {
        try {

          val actorFactoryBytes =
            if (shouldCompressData) LZF.uncompress(zkClient.connection.readData(actorFactoryPath, new Stat, false))
            else zkClient.connection.readData(actorFactoryPath, new Stat, false)

          val actorFactory =
            Serialization.deserialize(actorFactoryBytes, classOf[() ⇒ LocalActorRef], None) match {
              case Left(error)     ⇒ throw error
              case Right(instance) ⇒ instance.asInstanceOf[() ⇒ LocalActorRef]
            }

          Right(actorFactory)
        } catch {
          case e: KeeperException.NoNodeException ⇒ Left(e)
        }
      }
    }) match {
      case Left(exception) ⇒ throw exception
      case Right(actorFactory) ⇒
        val actorRef = actorFactory()

        EventHandler.debug(this,
          "Checking out actor [%s] to be used on node [%s] as local actor"
            .format(actorAddress, nodeName))

        val uuid = actorRef.uuid

        // create UUID registry
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorUuidRegistryPathFor(uuid)))

        // create UUID -> NODE mapping
        try {
          zkClient.createEphemeral(actorUuidRegistryNodePathFor(uuid), nodeName)
        } catch {
          case e: ZkNodeExistsException ⇒ zkClient.writeData(actorUuidRegistryNodePathFor(uuid), nodeName)
        }

        // create UUID -> ADDRESS
        try {
          zkClient.createEphemeral(actorUuidRegistryAddressPathFor(uuid), actorAddress)
        } catch {
          case e: ZkNodeExistsException ⇒ zkClient.writeData(actorUuidRegistryAddressPathFor(uuid), actorAddress)
        }

        // create UUID -> REMOTE ADDRESS (InetSocketAddress) mapping
        try {
          zkClient.createEphemeral(actorUuidRegistryRemoteAddressPathFor(uuid), remoteServerAddress)
        } catch {
          case e: ZkNodeExistsException ⇒ zkClient.writeData(actorUuidRegistryRemoteAddressPathFor(uuid), remoteServerAddress)
        }

        // create ADDRESS -> UUID mapping
        try {
          zkClient.createPersistent(actorAddressRegistryUuidPathFor(actorAddress), uuid)
        } catch {
          case e: ZkNodeExistsException ⇒ zkClient.writeData(actorAddressRegistryUuidPathFor(actorAddress), uuid)
        }

        // create NODE -> UUID mapping
        ignore[ZkNodeExistsException](zkClient.createPersistent(nodeToUuidsPathFor(nodeName, uuid), true))

        // create ADDRESS -> UUIDs mapping
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorAddressToUuidsPathFor(actorAddress, uuid)))

        actorRef.start()
        actorRef
    }
  } else None

  /**
   * Using (checking out) actor on a specific set of nodes.
   */
  def useActorOnNodes(nodes: Array[String], actorAddress: String, replicateFromUuid: Option[UUID] = None) {
    EventHandler.debug(this,
      "Sending command to nodes [%s] for checking out actor [%s]".format(nodes.mkString(", "), actorAddress))

    if (isConnected.isOn) {

      val builder = RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(USE)
        .setActorAddress(actorAddress)

      // set the UUID to replicated from - if available
      replicateFromUuid foreach (uuid ⇒ builder.setReplicateActorFromUuid(uuidToUuidProtocol(uuid)))

      val command = builder.build

      nodes foreach { node ⇒
        nodeConnections.get(node) foreach {
          case (_, connection) ⇒
            sendCommandToNode(connection, command, async = false)
        }
      }
    }
  }

  /**
   * Using (checking out) actor on all nodes in the cluster.
   */
  def useActorOnAllNodes(actorAddress: String, replicateFromUuid: Option[UUID] = None) {
    useActorOnNodes(membershipNodes, actorAddress, replicateFromUuid)
  }

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(node: String, actorAddress: String, replicateFromUuid: Option[UUID] = None) {
    useActorOnNodes(Array(node), actorAddress, replicateFromUuid)
  }

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorRef: ActorRef) {
    release(actorRef.address)
  }

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorAddress: String) {

    // FIXME 'Cluster.release' needs to notify all existing ClusterActorRef's that are using the instance that it is no
    // longer available. Then what to do? Should we even remove this method?

    if (isConnected.isOn) {
      ignore[ZkNoNodeException](zkClient.delete(actorAddressToNodesPathFor(actorAddress, nodeAddress.nodeName)))

      uuidsForActorAddress(actorAddress) foreach { uuid ⇒
        EventHandler.debug(this,
          "Releasing actor [%s] with UUID [%s] after usage".format(actorAddress, uuid))

        ignore[ZkNoNodeException](zkClient.deleteRecursive(nodeToUuidsPathFor(nodeAddress.nodeName, uuid)))
        ignore[ZkNoNodeException](zkClient.delete(actorUuidRegistryRemoteAddressPathFor(uuid)))
      }
    }
  }

  /**
   * Releases (checking in) all actors with a specific address on all nodes in the cluster where the actor is in 'use'.
   */
  private[akka] def releaseActorOnAllNodes(actorAddress: String) {
    if (isConnected.isOn) {
      EventHandler.debug(this,
        "Releasing (checking in) all actors with address [%s] on all nodes in cluster".format(actorAddress))

      val command = RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(RELEASE)
        .setActorAddress(actorAddress)
        .build

      nodesForActorsInUseWithAddress(actorAddress) foreach { node ⇒
        nodeConnections.get(node) foreach {
          case (_, connection) ⇒ sendCommandToNode(connection, command, async = true)
        }
      }
    }
  }

  /**
   * Creates an ActorRef with a Router to a set of clustered actors.
   */
  def ref(actorAddress: String, router: RouterType): ActorRef = if (isConnected.isOn) {
    val addresses = addressesForActor(actorAddress)
    EventHandler.debug(this,
      "Checking out cluster actor ref with address [%s] and router [%s] on [%s] connected to [\n\t%s]"
        .format(actorAddress, router, remoteServerAddress, addresses.map(_._2).mkString("\n\t")))

    val actorRef = Routing newRouter (router, addresses, actorAddress, Actor.TIMEOUT)
    addresses foreach {
      case (_, address) ⇒ clusterActorRefs.put(address, actorRef)
    }
    actorRef.start()

  } else throw new ClusterException("Not connected to cluster")

  /**
   * Returns the UUIDs of all actors checked out on this node.
   */
  private[akka] def uuidsForActorsInUse: Array[UUID] = uuidsForActorsInUseOnNode(nodeAddress.nodeName)

  /**
   * Returns the addresses of all actors checked out on this node.
   */
  def addressesForActorsInUse: Array[String] = actorAddressForUuids(uuidsForActorsInUse)

  /**
   * Returns the UUIDs of all actors registered in this cluster.
   */
  private[akka] def uuidsForClusteredActors: Array[UUID] = if (isConnected.isOn) {
    zkClient.getChildren(ACTOR_UUID_REGISTRY_PATH).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]]
  } else Array.empty[UUID]

  /**
   * Returns the addresses of all actors registered in this cluster.
   */
  def addressesForClusteredActors: Array[String] = actorAddressForUuids(uuidsForClusteredActors)

  /**
   * Returns the actor id for the actor with a specific UUID.
   */
  private[akka] def actorAddressForUuid(uuid: UUID): Option[String] = if (isConnected.isOn) {
    try {
      Some(zkClient.readData(actorUuidRegistryAddressPathFor(uuid)).asInstanceOf[String])
    } catch {
      case e: ZkNoNodeException ⇒ None
    }
  } else None

  /**
   * Returns the actor ids for all the actors with a specific UUID.
   */
  private[akka] def actorAddressForUuids(uuids: Array[UUID]): Array[String] =
    uuids map (actorAddressForUuid(_)) filter (_.isDefined) map (_.get)

  /**
   * Returns the actor UUIDs for actor ID.
   */
  private[akka] def uuidsForActorAddress(actorAddress: String): Array[UUID] = if (isConnected.isOn) {
    try {
      zkClient.getChildren(actorAddressToUuidsPathFor(actorAddress)).toList.toArray map {
        case c: CharSequence ⇒ new UUID(c)
      } filter (_ ne null)
    } catch {
      case e: ZkNoNodeException ⇒ Array[UUID]()
    }
  } else Array.empty[UUID]

  /**
   * Returns the node names of all actors in use with UUID.
   */
  private[akka] def nodesForActorsInUseWithAddress(actorAddress: String): Array[String] = if (isConnected.isOn) {
    try {
      zkClient.getChildren(actorAddressToNodesPathFor(actorAddress)).toList.toArray.asInstanceOf[Array[String]]
    } catch {
      case e: ZkNoNodeException ⇒ Array[String]()
    }
  } else Array.empty[String]

  /**
   * Returns the UUIDs of all actors in use registered on a specific node.
   */
  private[akka] def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID] = if (isConnected.isOn) {
    try {
      zkClient.getChildren(nodeToUuidsPathFor(nodeName)).toList.toArray map {
        case c: CharSequence ⇒ new UUID(c)
      } filter (_ ne null)
    } catch {
      case e: ZkNoNodeException ⇒ Array[UUID]()
    }
  } else Array.empty[UUID]

  /**
   * Returns the addresses of all actors in use registered on a specific node.
   */
  def addressesForActorsInUseOnNode(nodeName: String): Array[String] = if (isConnected.isOn) {
    val uuids =
      try {
        zkClient.getChildren(nodeToUuidsPathFor(nodeName)).toList.toArray map {
          case c: CharSequence ⇒ new UUID(c)
        } filter (_ ne null)
      } catch {
        case e: ZkNoNodeException ⇒ Array[UUID]()
      }
    actorAddressForUuids(uuids)
  } else Array.empty[String]

  /**
   * Returns Serializer for actor with specific address.
   */
  def serializerForActor(actorAddress: String): Serializer = try {
    Serialization.serializerByIdentity(zkClient.readData(actorAddressRegistrySerializerPathFor(actorAddress), new Stat).asInstanceOf[String].toByte)
  } catch {
    case e: ZkNoNodeException ⇒ throw new IllegalStateException("No serializer found for actor with address [%s]".format(actorAddress))
  }

  /**
   * Returns addresses for nodes that the clustered actor is in use on.
   */
  def addressesForActor(actorAddress: String): Array[(UUID, InetSocketAddress)] = {
    try {
      for {
        uuid ← uuidsForActorAddress(actorAddress)
      } yield {
        val remoteAddress = zkClient.readData(actorUuidRegistryRemoteAddressPathFor(uuid)).asInstanceOf[InetSocketAddress]
        (uuid, remoteAddress)
      }
    } catch {
      case e: ZkNoNodeException ⇒
        EventHandler.warning(this,
          "Could not retrieve remote socket address for node hosting actor [%s] due to: %s"
            .format(actorAddress, e.toString))
        Array[(UUID, InetSocketAddress)]()
    }
  }

  // =======================================
  // Compute Grid
  // =======================================

  /**
   * Send a function 'Function0[Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   */
  def send(f: Function0[Unit], replicationFactor: Int) {
    Serialization.serialize(f) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN0_UNIT)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        nodeConnectionsForReplicationFactor(replicationFactor) foreach (_ ! message)
    }
  }

  /**
   * Send a function 'Function0[Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function0[Any], replicationFactor: Int): List[Future[Any]] = {
    Serialization.serialize(f) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN0_ANY)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        val results = nodeConnectionsForReplicationFactor(replicationFactor) map (_ ? message)
        results.toList.asInstanceOf[List[Future[Any]]]
    }
  }

  /**
   * Send a function 'Function1[Any, Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   */
  def send(f: Function1[Any, Unit], arg: Any, replicationFactor: Int) {
    Serialization.serialize((f, arg)) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN1_ARG_UNIT)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        nodeConnectionsForReplicationFactor(replicationFactor) foreach (_ ! message)
    }
  }

  /**
   * Send a function 'Function1[Any, Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function1[Any, Any], arg: Any, replicationFactor: Int): List[Future[Any]] = {
    Serialization.serialize((f, arg)) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN1_ARG_ANY)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        val results = nodeConnectionsForReplicationFactor(replicationFactor) map (_ ? message)
        results.toList.asInstanceOf[List[Future[Any]]]
    }
  }

  // =======================================
  // Config
  // =======================================

  /**
   * Stores a configuration element under a specific key.
   * If the key already exists then it will be overwritten.
   */
  def setConfigElement(key: String, bytes: Array[Byte]) {
    val compressedBytes = if (shouldCompressData) LZF.compress(bytes) else bytes
    EventHandler.debug(this,
      "Adding config value [%s] under key [%s] in cluster registry".format(key, compressedBytes))
    zkClient.retryUntilConnected(new Callable[Either[Unit, Exception]]() {
      def call: Either[Unit, Exception] = {
        try {
          Left(zkClient.connection.create(configurationPathFor(key), compressedBytes, CreateMode.PERSISTENT))
        } catch {
          case e: KeeperException.NodeExistsException ⇒
            try {
              Left(zkClient.connection.writeData(configurationPathFor(key), compressedBytes))
            } catch {
              case e: Exception ⇒ Right(e)
            }
        }
      }
    }) match {
      case Left(_)          ⇒ /* do nothing */
      case Right(exception) ⇒ throw exception
    }
  }

  /**
   * Returns the config element for the key or NULL if no element exists under the key.
   * Returns <code>Some(element)</code> if it exists else <code>None</code>
   */
  def getConfigElement(key: String): Option[Array[Byte]] = try {
    Some(zkClient.connection.readData(configurationPathFor(key), new Stat, true))
  } catch {
    case e: KeeperException.NoNodeException ⇒ None
  }

  /**
   * Removes configuration element for a specific key.
   * Does nothing if the key does not exist.
   */
  def removeConfigElement(key: String) {
    ignore[ZkNoNodeException] {
      EventHandler.debug(this,
        "Removing config element with key [%s] from cluster registry".format(key))
      zkClient.deleteRecursive(configurationPathFor(key))
    }
  }

  /**
   * Returns a list with all config element keys.
   */
  def getConfigElementKeys: Array[String] = zkClient.getChildren(CONFIGURATION_PATH).toList.toArray.asInstanceOf[Array[String]]

  // =======================================
  // Private
  // =======================================

  private def sendCommandToNode(connection: ActorRef, command: RemoteDaemonMessageProtocol, async: Boolean = true) {
    if (async) {
      connection ! command
    } else {
      (connection ? (command, remoteDaemonAckTimeout)).as[Status] match {

        case Some(Success) ⇒
          EventHandler.debug(this, "Replica for [%s] successfully created".format(connection.address))

        case Some(Failure(cause)) ⇒
          EventHandler.error(cause, this, cause.toString)
          throw cause

        case None ⇒
          val error = new ClusterException(
            "Operation to instantiate replicas throughout the cluster timed out")
          EventHandler.error(error, this, error.toString)
          throw error
      }
    }
  }

  private[cluster] def membershipPathFor(node: String): String = "%s/%s".format(MEMBERSHIP_PATH, node)

  private[cluster] def configurationPathFor(key: String): String = "%s/%s".format(CONFIGURATION_PATH, key)

  private[cluster] def actorAddressToNodesPathFor(actorAddress: String): String = "%s/%s".format(ACTOR_ADDRESS_NODES_TO_PATH, actorAddress)

  private[cluster] def actorAddressToNodesPathFor(actorAddress: String, nodeName: String): String = "%s/%s".format(actorAddressToNodesPathFor(actorAddress), nodeName)

  private[cluster] def nodeToUuidsPathFor(node: String): String = "%s/%s".format(NODE_TO_ACTOR_UUIDS_PATH, node)

  private[cluster] def nodeToUuidsPathFor(node: String, uuid: UUID): String = "%s/%s/%s".format(NODE_TO_ACTOR_UUIDS_PATH, node, uuid)

  private[cluster] def actorAddressRegistryPathFor(actorAddress: String): String = "%s/%s".format(ACTOR_ADDRESS_REGISTRY_PATH, actorAddress)

  private[cluster] def actorAddressRegistrySerializerPathFor(actorAddress: String): String = "%s/%s".format(actorAddressRegistryPathFor(actorAddress), "serializer")

  private[cluster] def actorAddressRegistryUuidPathFor(actorAddress: String): String = "%s/%s".format(actorAddressRegistryPathFor(actorAddress), "uuid")

  private[cluster] def actorUuidRegistryPathFor(uuid: UUID): String = "%s/%s".format(ACTOR_UUID_REGISTRY_PATH, uuid)

  private[cluster] def actorUuidRegistryNodePathFor(uuid: UUID): String = "%s/%s".format(actorUuidRegistryPathFor(uuid), "node")

  private[cluster] def actorUuidRegistryAddressPathFor(uuid: UUID): String = "%s/%s".format(actorUuidRegistryPathFor(uuid), "address")

  private[cluster] def actorUuidRegistryRemoteAddressPathFor(uuid: UUID): String = "%s/%s".format(actorUuidRegistryPathFor(uuid), "remote-address")

  private[cluster] def actorAddressToUuidsPathFor(actorAddress: String): String = "%s/%s".format(ACTOR_ADDRESS_TO_UUIDS_PATH, actorAddress.replace('.', '_'))

  private[cluster] def actorAddressToUuidsPathFor(actorAddress: String, uuid: UUID): String = "%s/%s".format(actorAddressToUuidsPathFor(actorAddress), uuid)

  /**
   * Returns a random set with node names of size 'replicationFactor'.
   * Default replicationFactor is 0, which returns the empty Set.
   */
  private def nodesForReplicationFactor(replicationFactor: Int = 0, actorAddress: Option[String] = None): Set[String] = {
    var replicaNames = Set.empty[String]
    val nrOfClusterNodes = nodeConnections.size

    if (replicationFactor < 1) return replicaNames
    if (nrOfClusterNodes < replicationFactor) throw new IllegalArgumentException(
      "Replication factor [" + replicationFactor +
        "] is greater than the number of available nodeNames [" + nrOfClusterNodes + "]")

    val preferredNodes =
      if (actorAddress.isDefined) {
        // use 'preferred-nodes' in deployment config for the actor
        Deployer.deploymentFor(actorAddress.get) match {
          case Deploy(_, _, _, Clustered(nodes, _, _)) ⇒
            nodes map (node ⇒ DeploymentConfig.nodeNameFor(node)) take replicationFactor
          case _ ⇒
            throw new ClusterException("Actor [" + actorAddress.get + "] is not configured as clustered")
        }
      } else Vector.empty[String]

    for {
      nodeName ← preferredNodes
      key ← nodeConnections.keys
      if key == nodeName
    } replicaNames = replicaNames + nodeName

    val nrOfCurrentReplicaNames = replicaNames.size

    val replicaSet =
      if (nrOfCurrentReplicaNames > replicationFactor) throw new IllegalStateException("Replica set is larger than replication factor")
      else if (nrOfCurrentReplicaNames == replicationFactor) replicaNames
      else {
        val random = new java.util.Random(System.currentTimeMillis)
        while (replicaNames.size < replicationFactor) {
          replicaNames = replicaNames + membershipNodes(random.nextInt(nrOfClusterNodes))
        }
        replicaNames
      }

    EventHandler.debug(this,
      "Picked out replica set [%s] for actor [%s]".format(replicaSet.mkString(", "), actorAddress))

    replicaSet
  }

  /**
   * Returns a random set with replica connections of size 'replicationFactor'.
   * Default replicationFactor is 0, which returns the empty Set.
   */
  private def nodeConnectionsForReplicationFactor(replicationFactor: Int = 0, actorAddress: Option[String] = None): Set[ActorRef] = {
    for {
      node ← nodesForReplicationFactor(replicationFactor, actorAddress)
      connectionOption ← nodeConnections.get(node)
      connection ← connectionOption
      actorRef ← connection._2
    } yield actorRef
  }

  private val connectToAllNewlyArrivedMembershipNodesInClusterLock = new AtomicBoolean(false)

  /**
   * Update the list of connections to other nodes in the cluster.
   *
   * @returns a Map with the remote socket addresses to of disconnected node connections
   */
  private[cluster] def connectToAllNewlyArrivedMembershipNodesInCluster(
    newlyConnectedMembershipNodes: Traversable[String],
    newlyDisconnectedMembershipNodes: Traversable[String]): Map[String, InetSocketAddress] = {

    // cache the disconnected connections in a map, needed for fail-over of these connections later
    var disconnectedConnections = Map.empty[String, InetSocketAddress]
    newlyDisconnectedMembershipNodes foreach { node ⇒
      disconnectedConnections += (node -> (nodeConnections(node) match {
        case (address, _) ⇒ address
      }))
    }

    if (connectToAllNewlyArrivedMembershipNodesInClusterLock.compareAndSet(false, true)) {
      try {
        // remove connections to failed nodes
        newlyDisconnectedMembershipNodes foreach (nodeConnections.remove(_))

        // add connections newly arrived nodes
        newlyConnectedMembershipNodes foreach { node ⇒
          if (!nodeConnections.contains(node)) {
            // only connect to each replica once

            remoteSocketAddressForNode(node) foreach { address ⇒
              EventHandler.debug(this,
                "Setting up connection to node with nodename [%s] and address [%s]".format(node, address))

              val clusterDaemon = Actor.remote.actorFor(RemoteClusterDaemon.Address, address.getHostName, address.getPort).start()
              nodeConnections.put(node, (address, clusterDaemon))
            }
          }
        }
      } finally {
        connectToAllNewlyArrivedMembershipNodesInClusterLock.set(false)
      }
    }

    disconnectedConnections
  }

  private[cluster] def joinCluster() {
    try {
      EventHandler.info(this,
        "Joining cluster as membership node [%s] on [%s]".format(nodeAddress, membershipNodePath))
      zkClient.createEphemeral(membershipNodePath, remoteServerAddress)
    } catch {
      case e: ZkNodeExistsException ⇒
        val error = new ClusterException(
          "Can't join the cluster. The node name [" + nodeAddress.nodeName + "] is already in by another node")
        EventHandler.error(error, this, error.toString)
        throw error
    }
    ignore[ZkNodeExistsException](zkClient.createPersistent(nodeToUuidsPathFor(nodeAddress.nodeName)))
  }

  private[cluster] def joinLeaderElection(): Boolean = {
    EventHandler.info(this, "Node [%s] is joining leader election".format(nodeAddress.nodeName))
    try {
      leaderLock.lock
    } catch {
      case e: KeeperException.NodeExistsException ⇒ false
    }
  }

  private[cluster] def remoteSocketAddressForNode(node: String): Option[InetSocketAddress] = {
    try {
      Some(zkClient.readData(membershipPathFor(node), new Stat).asInstanceOf[InetSocketAddress])
    } catch {
      case e: ZkNoNodeException ⇒ None
    }
  }

  private[cluster] def failOverClusterActorRefConnections(from: InetSocketAddress, to: InetSocketAddress) {
    EventHandler.info(this, "failOverClusterActorRef from %s to %s".format(from, to))
    clusterActorRefs.values(from) foreach (_.failOver(from, to))
  }

  private[cluster] def migrateActorsOnFailedNodes(
    failedNodes: List[String],
    currentClusterNodes: List[String],
    oldClusterNodes: List[String],
    disconnectedConnections: Map[String, InetSocketAddress]) {

    failedNodes.foreach { failedNodeName ⇒

      val failedNodeAddress = NodeAddress(nodeAddress.clusterName, failedNodeName)

      val myIndex = oldClusterNodes.indexWhere(_.endsWith(nodeAddress.nodeName))
      val failedNodeIndex = oldClusterNodes.indexWhere(_ == failedNodeName)

      // Migrate to the successor of the failed node (using a sorted circular list of the node names)
      if ((failedNodeIndex == 0 && myIndex == oldClusterNodes.size - 1) || // No leftmost successor exists, check the tail
        (failedNodeIndex == myIndex + 1)) {
        // Am I the leftmost successor?

        // Takes the lead of migrating the actors. Not all to this node.
        // All to this node except if the actor already resides here, then pick another node it is not already on.

        // Yes I am the node to migrate the actor to (can only be one in the cluster)
        val actorUuidsForFailedNode = zkClient.getChildren(nodeToUuidsPathFor(failedNodeName)).toList

        actorUuidsForFailedNode.foreach { uuidAsString ⇒
          EventHandler.debug(this,
            "Cluster node [%s] has failed, migrating actor with UUID [%s] to [%s]"
              .format(failedNodeName, uuidAsString, nodeAddress.nodeName))

          val uuid = uuidFrom(uuidAsString)
          val actorAddress = actorAddressForUuid(uuid).getOrElse(
            throw new IllegalStateException("No actor address found for UUID [" + uuidAsString + "]"))

          val migrateToNodeAddress =
            if (isInUseOnNode(actorAddress)) {
              // already in use on this node, pick another node to instantiate the actor on
              val replicaNodesForActor = nodesForActorsInUseWithAddress(actorAddress)
              val nodesAvailableForMigration = (currentClusterNodes.toSet diff failedNodes.toSet) diff replicaNodesForActor.toSet

              if (nodesAvailableForMigration.isEmpty) throw new ClusterException(
                "Can not migrate actor to new node since there are not any available nodes left. " +
                  "(However, the actor already has >1 replica in cluster, so we are ok)")

              NodeAddress(nodeAddress.clusterName, nodesAvailableForMigration.head)
            } else {
              // actor is not in use on this node, migrate it here
              nodeAddress
            }

          // if actor is replicated => pass along the UUID for the actor to replicate from (replay transaction log etc.)
          val replicateFromUuid =
            if (isReplicated(actorAddress)) Some(uuid)
            else None

          migrateWithoutCheckingThatActorResidesOnItsHomeNode(
            failedNodeAddress,
            migrateToNodeAddress,
            actorAddress,
            replicateFromUuid)
        }

        // notify all available nodes that they should fail-over all connections from 'from' to 'to'
        val from = disconnectedConnections(failedNodeName)
        val to = remoteServerAddress

        Serialization.serialize((from, to)) match {
          case Left(error) ⇒ throw error
          case Right(bytes) ⇒

            val command = RemoteDaemonMessageProtocol.newBuilder
              .setMessageType(FAIL_OVER_CONNECTIONS)
              .setPayload(ByteString.copyFrom(bytes))
              .build

            // FIXME now we are broadcasting to ALL nodes in the cluster even though a fraction might have a reference to the actors - should that be fixed?
            nodeConnections.values foreach {
              case (_, connection) ⇒ sendCommandToNode(connection, command, async = true)
            }
        }
      }
    }
  }

  /**
   * Used when the ephemeral "home" node is already gone, so we can't check if it is available.
   */
  private def migrateWithoutCheckingThatActorResidesOnItsHomeNode(
    from: NodeAddress, to: NodeAddress, actorAddress: String, replicateFromUuid: Option[UUID]) {

    EventHandler.debug(this, "Migrating actor [%s] from node [%s] to node [%s]".format(actorAddress, from, to))
    if (!isInUseOnNode(actorAddress, to)) {
      release(actorAddress)

      val remoteAddress = remoteSocketAddressForNode(to.nodeName).getOrElse(throw new ClusterException("No remote address registered for [" + to.nodeName + "]"))

      ignore[ZkNodeExistsException](zkClient.createEphemeral(actorAddressToNodesPathFor(actorAddress, to.nodeName)))

      ignore[ZkNoNodeException](zkClient.delete(actorAddressToNodesPathFor(actorAddress, from.nodeName)))

      // FIXME who takes care of this line?
      //ignore[ZkNoNodeException](zkClient.delete(nodeToUuidsPathFor(from.nodeName, uuid)))

      // 'use' (check out) actor on the remote 'to' node
      useActorOnNode(to.nodeName, actorAddress, replicateFromUuid)
    }
  }

  private def createZooKeeperPathStructureIfNeeded() {
    ignore[ZkNodeExistsException] {
      zkClient.create(CLUSTER_PATH, null, CreateMode.PERSISTENT)
      EventHandler.info(this, "Created node [%s]".format(CLUSTER_PATH))
    }

    basePaths.foreach { path ⇒
      try {
        ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
        EventHandler.debug(this, "Created node [%s]".format(path))
      } catch {
        case e ⇒
          val error = new ClusterException(e.toString)
          EventHandler.error(error, this)
          throw error
      }
    }
  }

  private def registerListeners() = {
    zkClient.subscribeStateChanges(stateListener)
    zkClient.subscribeChildChanges(MEMBERSHIP_PATH, membershipListener)
  }

  private def unregisterListeners() = {
    zkClient.unsubscribeStateChanges(stateListener)
    zkClient.unsubscribeChildChanges(MEMBERSHIP_PATH, membershipListener)
  }

  private def fetchMembershipNodes() {
    val membershipChildren = zkClient.getChildren(MEMBERSHIP_PATH)
    locallyCachedMembershipNodes.clear()
    membershipChildren.iterator.foreach(locallyCachedMembershipNodes.add)
    connectToAllNewlyArrivedMembershipNodesInCluster(membershipNodes, Nil)
  }

  private def isReplicated(actorAddress: String): Boolean = DeploymentConfig.isReplicated(Deployer.deploymentFor(actorAddress))

  private def createMBean = {
    val clusterMBean = new StandardMBean(classOf[ClusterNodeMBean]) with ClusterNodeMBean {

      override def start() = self.start()

      override def stop() = self.shutdown()

      override def disconnect() = self.disconnect()

      override def reconnect() = self.reconnect()

      override def resign() = self.resign()

      override def isConnected = self.isConnected.isOn

      override def getNodeAddres = self.nodeAddress

      override def getRemoteServerHostname = self.hostname

      override def getRemoteServerPort = self.port

      override def getNodeName = self.nodeAddress.nodeName

      override def getClusterName = self.nodeAddress.clusterName

      override def getZooKeeperServerAddresses = self.zkServerAddresses

      override def getMemberNodes = self.locallyCachedMembershipNodes.iterator.map(_.toString).toArray

      override def getLeaderLockName = self.leader.toString

      override def isLeader = self.isLeader

      override def getUuidsForActorsInUse = self.uuidsForActorsInUse.map(_.toString).toArray

      override def getAddressesForActorsInUse = self.addressesForActorsInUse.map(_.toString).toArray

      override def getUuidsForClusteredActors = self.uuidsForClusteredActors.map(_.toString).toArray

      override def getAddressesForClusteredActors = self.addressesForClusteredActors.map(_.toString).toArray

      override def getNodesForActorInUseWithAddress(address: String) = self.nodesForActorsInUseWithAddress(address)

      override def getUuidsForActorsInUseOnNode(nodeName: String) = self.uuidsForActorsInUseOnNode(nodeName).map(_.toString).toArray

      override def getAddressesForActorsInUseOnNode(nodeName: String) = self.addressesForActorsInUseOnNode(nodeName).map(_.toString).toArray

      override def setConfigElement(key: String, value: String): Unit = self.setConfigElement(key, value.getBytes("UTF-8"))

      override def getConfigElement(key: String) = new String(self.getConfigElement(key).getOrElse(Array[Byte]()), "UTF-8")

      override def removeConfigElement(key: String): Unit = self.removeConfigElement(key)

      override def getConfigElementKeys = self.getConfigElementKeys.toArray

      override def getMemberShipPathFor(node: String) = self.membershipPathFor(node)

      override def getConfigurationPathFor(key: String) = self.configurationPathFor(key)

      override def getActorAddresstoNodesPathFor(actorAddress: String) = self.actorAddressToNodesPathFor(actorAddress)

      override def getActorAddressToNodesPathForWithNodeName(actorAddress: String, nodeName: String) = self.actorAddressToNodesPathFor(actorAddress, nodeName)

      override def getNodeToUuidsPathFor(node: String) = self.nodeToUuidsPathFor(node)

      override def getNodeToUuidsPathFor(node: String, uuid: UUID) = self.nodeToUuidsPathFor(node, uuid)

      override def getActorAddressRegistryPathFor(actorAddress: String) = self.actorAddressRegistryPathFor(actorAddress)

      override def getActorAddressRegistrySerializerPathFor(actorAddress: String) = self.actorAddressRegistrySerializerPathFor(actorAddress)

      override def getActorAddressRegistryUuidPathFor(actorAddress: String) = self.actorAddressRegistryUuidPathFor(actorAddress)

      override def getActorUuidRegistryNodePathFor(uuid: UUID) = self.actorUuidRegistryNodePathFor(uuid)

      override def getActorUuidRegistryRemoteAddressPathFor(uuid: UUID) = self.actorUuidRegistryNodePathFor(uuid)

      override def getActorAddressToUuidsPathFor(actorAddress: String) = self.actorAddressToUuidsPathFor(actorAddress)

      override def getActorAddressToUuidsPathForWithNodeName(actorAddress: String, uuid: UUID) = self.actorAddressToUuidsPathFor(actorAddress, uuid)
    }

    JMX.register(clusterJmxObjectName, clusterMBean)

    // FIXME need monitoring to lookup the cluster MBean dynamically
    // Monitoring.registerLocalMBean(clusterJmxObjectName, clusterMBean)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MembershipChildListener(self: ClusterNode) extends IZkChildListener with ErrorHandler {
  def handleChildChange(parentPath: String, currentChilds: JList[String]) {
    withErrorHandler {
      if (currentChilds ne null) {
        val currentClusterNodes = currentChilds.toList
        if (!currentClusterNodes.isEmpty) EventHandler.debug(this,
          "MembershipChildListener at [%s] has children [%s]"
            .format(self.nodeAddress.nodeName, currentClusterNodes.mkString(" ")))

        // take a snapshot of the old cluster nodes and then update the list with the current connected nodes in the cluster
        val oldClusterNodes = self.locallyCachedMembershipNodes.toArray.toSet.asInstanceOf[Set[String]]
        self.locallyCachedMembershipNodes.clear()
        currentClusterNodes foreach (self.locallyCachedMembershipNodes.add)

        val newlyConnectedMembershipNodes = (Set(currentClusterNodes: _*) diff oldClusterNodes).toList
        val newlyDisconnectedMembershipNodes = (oldClusterNodes diff Set(currentClusterNodes: _*)).toList

        // update the connections with the new set of cluster nodes
        val disconnectedConnections = self.connectToAllNewlyArrivedMembershipNodesInCluster(newlyConnectedMembershipNodes, newlyDisconnectedMembershipNodes)

        // if node(s) left cluster then migrate actors residing on the failed node
        if (!newlyDisconnectedMembershipNodes.isEmpty)
          self.migrateActorsOnFailedNodes(newlyDisconnectedMembershipNodes, currentClusterNodes, oldClusterNodes.toList, disconnectedConnections)

        // publish NodeConnected and NodeDisconnect events to the listeners
        newlyConnectedMembershipNodes foreach (node ⇒ self.publish(NodeConnected(node)))
        newlyDisconnectedMembershipNodes foreach (node ⇒ self.publish(NodeDisconnected(node)))
      }
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class StateListener(self: ClusterNode) extends IZkStateListener {
  def handleStateChanged(state: KeeperState) {
    state match {
      case KeeperState.SyncConnected ⇒
        EventHandler.debug(this, "Cluster node [%s] - Connected".format(self.nodeAddress))
        self.publish(ThisNode.Connected)
      case KeeperState.Disconnected ⇒
        EventHandler.debug(this, "Cluster node [%s] - Disconnected".format(self.nodeAddress))
        self.publish(ThisNode.Disconnected)
      case KeeperState.Expired ⇒
        EventHandler.debug(this, "Cluster node [%s] - Expired".format(self.nodeAddress))
        self.publish(ThisNode.Expired)
    }
  }

  /**
   * Re-initialize after the zookeeper session has expired and a new session has been created.
   */
  def handleNewSession() {
    EventHandler.debug(this, "Session expired re-initializing node [%s]".format(self.nodeAddress))
    self.initializeNode()
    self.publish(NewSession)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ErrorHandler {
  def withErrorHandler[T](body: ⇒ T) = {
    try {
      ignore[ZkInterruptedException](body) // FIXME Is it good to ignore ZkInterruptedException? If not, how should we handle it?
    } catch {
      case e: Throwable ⇒
        EventHandler.error(e, this, e.toString)
        throw e
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClusterDaemon {
  val Address = "akka-cluster-daemon".intern

  // FIXME configure computeGridDispatcher to what?
  val computeGridDispatcher = Dispatchers.newDispatcher("akka:compute-grid").build
}

/**
 * Internal "daemon" actor for cluster internal communication.
 *
 * It acts as the brain of the cluster that responds to cluster events (messages) and undertakes action.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClusterDaemon(cluster: ClusterNode) extends Actor {

  import RemoteClusterDaemon._
  import Cluster._

  self.dispatcher = Dispatchers.newPinnedDispatcher(self)

  override def preRestart(reason: Throwable, msg: Option[Any]) {
    EventHandler.debug(this, "RemoteClusterDaemon failed due to [%s] restarting...".format(reason))
  }

  def receive: Receive = {
    case message: RemoteDaemonMessageProtocol ⇒
      EventHandler.debug(this,
        "Received command [\n%s] to RemoteClusterDaemon on node [%s]".format(message, cluster.nodeAddress.nodeName))

      message.getMessageType match {
        case USE                    ⇒ handleUse(message)
        case RELEASE                ⇒ handleRelease(message)
        case START                  ⇒ cluster.start()
        case STOP                   ⇒ cluster.shutdown()
        case DISCONNECT             ⇒ cluster.disconnect()
        case RECONNECT              ⇒ cluster.reconnect()
        case RESIGN                 ⇒ cluster.resign()
        case FAIL_OVER_CONNECTIONS  ⇒ handleFailover(message)
        case FUNCTION_FUN0_UNIT     ⇒ handle_fun0_unit(message)
        case FUNCTION_FUN0_ANY      ⇒ handle_fun0_any(message)
        case FUNCTION_FUN1_ARG_UNIT ⇒ handle_fun1_arg_unit(message)
        case FUNCTION_FUN1_ARG_ANY  ⇒ handle_fun1_arg_any(message)
        //TODO: should we not deal with unrecognized message types?
      }

    case unknown ⇒ EventHandler.warning(this, "Unknown message [%s]".format(unknown))
  }

  def handleRelease(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    if (message.hasActorUuid) {
      cluster.actorAddressForUuid(uuidProtocolToUuid(message.getActorUuid)) foreach { address ⇒
        cluster.release(address)
      }
    } else if (message.hasActorAddress) {
      cluster release message.getActorAddress
    } else {
      EventHandler.warning(this,
        "None of 'uuid' or 'actorAddress'' is specified, ignoring remote cluster daemon command [%s]".format(message))
    }
  }

  def handleUse(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    def deserializeMessages(entriesAsBytes: Vector[Array[Byte]]): Vector[AnyRef] = {
      import akka.remote.protocol.RemoteProtocol._
      import akka.remote.MessageSerializer

      entriesAsBytes map { bytes ⇒
        val messageBytes =
          if (Cluster.shouldCompressData) LZF.uncompress(bytes)
          else bytes
        MessageSerializer.deserialize(MessageProtocol.parseFrom(messageBytes), None)
      }
    }

    def createActorRefToUseForReplay(snapshotAsBytes: Option[Array[Byte]], actorAddress: String, newActorRef: LocalActorRef): ActorRef = {
      snapshotAsBytes match {

        // we have a new actor ref - the snapshot
        case Some(bytes) ⇒
          // stop the new actor ref and use the snapshot instead
          //TODO: What if that actor already has been retrieved and is being used??
          //So do we have a race here?
          cluster.remoteService.unregister(actorAddress)

          // deserialize the snapshot actor ref and register it as remote actor
          val uncompressedBytes =
            if (Cluster.shouldCompressData) LZF.uncompress(bytes)
            else bytes

          val snapshotActorRef = fromBinary(uncompressedBytes, newActorRef.uuid).start()
          cluster.remoteService.register(actorAddress, snapshotActorRef)

          // FIXME we should call 'stop()' here (to GC the actor), but can't since that will currently
          //shut down the TransactionLog for this UUID - since both this actor and the new snapshotActorRef
          //have the same UUID (which they should)
          //newActorRef.stop()

          snapshotActorRef

        // we have no snapshot - use the new actor ref
        case None ⇒
          newActorRef
      }
    }

    try {
      if (message.hasActorAddress) {
        val actorAddress = message.getActorAddress
        cluster.serializerForActor(actorAddress) foreach { serializer ⇒
          cluster.use(actorAddress, serializer) foreach { newActorRef ⇒
            cluster.remoteService.register(actorAddress, newActorRef)

            if (message.hasReplicateActorFromUuid) {
              // replication is used - fetch the messages and replay them
              val replicateFromUuid = uuidProtocolToUuid(message.getReplicateActorFromUuid)
              val deployment = Deployer.deploymentFor(actorAddress)
              val replicationScheme = DeploymentConfig.replicationSchemeFor(deployment).getOrElse(
                throw new IllegalStateException(
                  "Actor [" + actorAddress + "] should have been configured as a replicated actor but could not find its ReplicationScheme"))
              val isWriteBehind = DeploymentConfig.isWriteBehindReplication(replicationScheme)

              try {
                // get the transaction log for the actor UUID
                val readonlyTxLog = TransactionLog.logFor(replicateFromUuid.toString, isWriteBehind, replicationScheme)

                // get the latest snapshot (Option[Array[Byte]]) and all the subsequent messages (Array[Byte])
                val (snapshotAsBytes, entriesAsBytes) = readonlyTxLog.latestSnapshotAndSubsequentEntries

                // deserialize and restore actor snapshot. This call will automatically recreate a transaction log.
                val actorRef = createActorRefToUseForReplay(snapshotAsBytes, actorAddress, newActorRef)

                // deserialize the messages
                val messages: Vector[AnyRef] = deserializeMessages(entriesAsBytes)

                EventHandler.info(this, "Replaying [%s] messages to actor [%s]".format(messages.size, actorAddress))

                // replay all messages
                messages foreach { message ⇒
                  EventHandler.debug(this, "Replaying message [%s] to actor [%s]".format(message, actorAddress))

                  // FIXME how to handle '?' messages?
                  // We can *not* replay them with the correct semantics. Should we:
                  // 1. Ignore/drop them and log warning?
                  // 2. Throw exception when about to log them?
                  // 3. Other?
                  actorRef ! message
                }

              } catch {
                case e: Throwable ⇒
                  EventHandler.error(e, this, e.toString)
                  throw e
              }
            }
          }
        }
      } else {
        EventHandler.error(this, "Actor 'address' is not defined, ignoring remote cluster daemon command [%s]".format(message))
      }
      self.reply(Success)
    } catch {
      case error: Throwable ⇒
        self.reply(Failure(error))
        throw error
    }
  }

  def handle_fun0_unit(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    localActorOf(new Actor() {
      self.dispatcher = computeGridDispatcher

      def receive = {
        case f: Function0[_] ⇒ try {
          f()
        } finally {
          self.stop()
        }
      }
    }).start ! payloadFor(message, classOf[Function0[Unit]])
  }

  def handle_fun0_any(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    localActorOf(new Actor() {
      self.dispatcher = computeGridDispatcher

      def receive = {
        case f: Function0[_] ⇒ try {
          self.reply(f())
        } finally {
          self.stop()
        }
      }
    }).start forward payloadFor(message, classOf[Function0[Any]])
  }

  def handle_fun1_arg_unit(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    localActorOf(new Actor() {
      self.dispatcher = computeGridDispatcher

      def receive = {
        case (fun: Function[_, _], param: Any) ⇒ try {
          fun.asInstanceOf[Any ⇒ Unit].apply(param)
        } finally {
          self.stop()
        }
      }
    }).start ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])
  }

  def handle_fun1_arg_any(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    localActorOf(new Actor() {
      self.dispatcher = computeGridDispatcher

      def receive = {
        case (fun: Function[_, _], param: Any) ⇒ try {
          self.reply(fun.asInstanceOf[Any ⇒ Any](param))
        } finally {
          self.stop()
        }
      }
    }).start forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  }

  def handleFailover(message: ClusterProtocol.RemoteDaemonMessageProtocol) {
    val (from, to) = payloadFor(message, classOf[(InetSocketAddress, InetSocketAddress)])
    cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteDaemonMessageProtocol, clazz: Class[T]): T = {
    Serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}
