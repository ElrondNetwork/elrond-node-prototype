package network.elrond.p2p;

import java.util.ArrayList;
import java.util.List;

public class P2PBroadcastChanel {

    private P2PBroadcastChannelName name;
    private P2PConnection connection;
    private List<P2PChannelListener> listeners = new ArrayList<>();

    public P2PBroadcastChanel(P2PBroadcastChannelName chanelName, P2PConnection connection) {
        this.name = chanelName;
        this.connection = connection;
    }

    public P2PBroadcastChannelName getName() {
        return name;
    }

    public void setName(P2PBroadcastChannelName name) {
        this.name = name;
    }

    public P2PConnection getConnection() {
        return connection;
    }

    public void setConnection(P2PConnection connection) {
        this.connection = connection;
    }

    public List<P2PChannelListener> getListeners() {
        return listeners;
    }

    public void setListeners(List<P2PChannelListener> listeners) {
        this.listeners = listeners;
    }

    public String getChannelIdentifier() {
        String indent = name.toString();
        if (P2PChannelType.SHARD_LEVEL.equals(name.getType())) {
            indent += connection.getShard().getIndex();
        }
        return indent;
    }

    @Override
    public String toString() {
        return String.format("P2PBroadcastChannel{name=%s, listeners.size()=%d}", name, listeners.size());
    }
}

