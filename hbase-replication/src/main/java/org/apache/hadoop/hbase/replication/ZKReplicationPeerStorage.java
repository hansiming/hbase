/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.replication;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.replication.ReplicationPeerConfigUtil;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.util.CollectionUtils;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZKUtil.ZKUtilOp;
import org.apache.hadoop.hbase.zookeeper.ZKWatcher;
import org.apache.hadoop.hbase.zookeeper.ZNodePaths;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.generated.ReplicationProtos;

/**
 * ZK based replication peer storage.
 */
@InterfaceAudience.Private
class ZKReplicationPeerStorage extends ZKReplicationStorageBase implements ReplicationPeerStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ZKReplicationPeerStorage.class);

  public static final byte[] ENABLED_ZNODE_BYTES =
    toByteArray(ReplicationProtos.ReplicationState.State.ENABLED);
  public static final byte[] DISABLED_ZNODE_BYTES =
    toByteArray(ReplicationProtos.ReplicationState.State.DISABLED);

  /**
   * The name of the znode that contains the replication status of a remote slave (i.e. peer)
   * cluster.
   */
  private final String peerStateNodeName;

  /**
   * The name of the znode that contains a list of all remote slave (i.e. peer) clusters.
   */
  private final String peersZNode;

  public ZKReplicationPeerStorage(ZKWatcher zookeeper, Configuration conf) {
    super(zookeeper, conf);
    this.peerStateNodeName = conf.get("zookeeper.znode.replication.peers.state", "peer-state");
    String peersZNodeName = conf.get("zookeeper.znode.replication.peers", "peers");
    this.peersZNode = ZNodePaths.joinZNode(replicationZNode, peersZNodeName);
  }

  private String getPeerStateNode(String peerId) {
    return ZNodePaths.joinZNode(getPeerNode(peerId), peerStateNodeName);
  }

  private String getPeerNode(String peerId) {
    return ZNodePaths.joinZNode(peersZNode, peerId);
  }

  @Override
  public void addPeer(String peerId, ReplicationPeerConfig peerConfig, boolean enabled)
      throws ReplicationException {
    try {
      ZKUtil.createWithParents(zookeeper, peersZNode);
      ZKUtil.multiOrSequential(zookeeper,
        Arrays.asList(
          ZKUtilOp.createAndFailSilent(getPeerNode(peerId),
            ReplicationPeerConfigUtil.toByteArray(peerConfig)),
          ZKUtilOp.createAndFailSilent(getPeerStateNode(peerId),
            enabled ? ENABLED_ZNODE_BYTES : DISABLED_ZNODE_BYTES)),
        false);
    } catch (KeeperException e) {
      throw new ReplicationException("Could not add peer with id=" + peerId + ", peerConfif=>" +
        peerConfig + ", state=" + (enabled ? "ENABLED" : "DISABLED"), e);
    }
  }

  @Override
  public void removePeer(String peerId) throws ReplicationException {
    try {
      ZKUtil.deleteNodeRecursively(zookeeper, getPeerNode(peerId));
    } catch (KeeperException e) {
      throw new ReplicationException("Could not remove peer with id=" + peerId, e);
    }
  }

  @Override
  public void setPeerState(String peerId, boolean enabled) throws ReplicationException {
    byte[] stateBytes = enabled ? ENABLED_ZNODE_BYTES : DISABLED_ZNODE_BYTES;
    try {
      ZKUtil.setData(zookeeper, getPeerStateNode(peerId), stateBytes);
    } catch (KeeperException e) {
      throw new ReplicationException("Unable to change state of the peer with id=" + peerId, e);
    }
  }

  @Override
  public void updatePeerConfig(String peerId, ReplicationPeerConfig peerConfig)
      throws ReplicationException {
    try {
      ZKUtil.setData(this.zookeeper, getPeerNode(peerId),
        ReplicationPeerConfigUtil.toByteArray(peerConfig));
    } catch (KeeperException e) {
      throw new ReplicationException(
          "There was a problem trying to save changes to the " + "replication peer " + peerId, e);
    }
  }

  @Override
  public List<String> listPeerIds() throws ReplicationException {
    try {
      return CollectionUtils.nullToEmpty(ZKUtil.listChildrenAndWatchThem(zookeeper, peersZNode));
    } catch (KeeperException e) {
      throw new ReplicationException("Cannot get the list of peers", e);
    }
  }

  @Override
  public boolean isPeerEnabled(String peerId) throws ReplicationException {
    try {
      return Arrays.equals(ENABLED_ZNODE_BYTES,
        ZKUtil.getData(zookeeper, getPeerStateNode(peerId)));
    } catch (KeeperException | InterruptedException e) {
      throw new ReplicationException("Unable to get status of the peer with id=" + peerId, e);
    }
  }

  @Override
  public Optional<ReplicationPeerConfig> getPeerConfig(String peerId) throws ReplicationException {
    byte[] data;
    try {
      data = ZKUtil.getData(zookeeper, getPeerNode(peerId));
    } catch (KeeperException | InterruptedException e) {
      throw new ReplicationException("Error getting configuration for peer with id=" + peerId, e);
    }
    if (data == null || data.length == 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(ReplicationPeerConfigUtil.parsePeerFrom(data));
    } catch (DeserializationException e) {
      LOG.warn("Failed to parse replication peer config for peer with id=" + peerId, e);
      return Optional.empty();
    }
  }
}