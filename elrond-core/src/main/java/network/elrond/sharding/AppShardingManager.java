package network.elrond.sharding;

import net.tomp2p.peers.PeerAddress;
import network.elrond.application.AppState;
import network.elrond.core.CollectionUtil;
import network.elrond.p2p.P2PBroadcastChannel;
import network.elrond.p2p.P2PBroadcastChannelName;
import network.elrond.service.AppServiceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class AppShardingManager {

    private static final Logger logger = LogManager.getLogger(AppShardingManager.class);

    private Boolean isSeedNode;

    private static AppShardingManager instance = new AppShardingManager();

    public static AppShardingManager instance() {
        return instance;
    }

    public boolean isLeaderInShard(AppState state) {

        if (isSeedNode == null) {
            P2PBroadcastChannel chanel = state.getChannel(P2PBroadcastChannelName.BLOCK);
            HashSet<PeerAddress> peers = AppServiceProvider.getP2PBroadcastService().getPeersOnChannel(chanel);
            isSeedNode = CollectionUtil.size(peers) <= 1;
        }

        return isSeedNode;
    }

    public Integer getNumberNodesInShard(AppState state) {

        P2PBroadcastChannel channel = state.getChannel(P2PBroadcastChannelName.BLOCK);
        Integer nbPeers = getConnectedPeersOnChannel(channel).size();

        // account for self
        return nbPeers + 1;
    }

    public Integer getNumberNodesInNetwork(AppState state) {
        P2PBroadcastChannel channel = state.getChannel(P2PBroadcastChannelName.XTRANSACTION_BLOCK);
        Integer nbPeers = getConnectedPeersOnChannel(channel).size();

        // account for self
        return nbPeers + 1;
    }


    public List<String> getConnectedPeersOnChannel(P2PBroadcastChannel channel) {
        // get only alive nodes
        List<PeerAddress> allConnectedPeers = new ArrayList<>(channel.getConnection().getDht().peerBean().peerMap().all());

        return AppServiceProvider.getP2PBroadcastService().getPeersOnChannel(channel)
                .stream()
                .filter(Objects::nonNull).filter(allConnectedPeers::contains)
                .map(peerAddress -> peerAddress.peerId().toString())
                .sorted()
                .collect(Collectors.toList());
    }


    public List<String> getPeersOnShard(AppState state) {
        P2PBroadcastChannel chanel = state.getChannel(P2PBroadcastChannelName.BLOCK);

        List<PeerAddress> allPeers = state.getConnection().getPeer().peerBean().peerMap().all();

        // add self to the list
        allPeers.add(state.getConnection().getPeer().peerAddress());

        List<PeerAddress> peersOnChannel = AppServiceProvider.getP2PBroadcastService().getPeersOnChannel(chanel)
                .stream()
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        peersOnChannel.retainAll(allPeers);

        return peersOnChannel.stream()
                .map(peerAddress -> peerAddress.peerId().toString())
                .sorted()
                .collect(Collectors.toList());
    }

    public String getCurrentPeerID(AppState state) {
        return state.getConnection().getPeer().peerID().toString();
    }
}
