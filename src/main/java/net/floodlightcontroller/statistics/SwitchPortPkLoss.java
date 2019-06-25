package net.floodlightcontroller.statistics;

import java.util.Date;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import net.floodlightcontroller.statistics.web.SwitchPortBandwidthSerializer;

//@JsonSerialize(using=SwitchPortBandwidthSerializer.class)
public class SwitchPortPkLoss {
	private DatapathId id;//交换机ID
	private OFPort pt;//交换机端口
	private U64 rxPackets;
	private U64 txPackets;
	private U64 rxPkLoss;
	private U64 txPkLoss;
	
	private Date time;//更新时间
	private long starttime_ns;//开始时间，精确到纳秒

	
	private SwitchPortPkLoss() {}
	private SwitchPortPkLoss(DatapathId d, OFPort p, U64 rxPackets, U64 txPackets, U64 rxPkLoss, U64 txPkLoss) {
		id = d;
		pt = p;
		this.rxPackets = rxPackets;
		this.txPackets = txPackets;
		time = new Date();
		starttime_ns = System.nanoTime();
		this.rxPkLoss = rxPkLoss;
		this.txPkLoss = txPkLoss;
	}
	
	public static SwitchPortPkLoss of(DatapathId d, OFPort p, U64 rxPackets, U64 txPackets, U64 rxPkLoss, U64 txPkLoss) {
		if (d == null) {
			throw new IllegalArgumentException("Datapath ID cannot be null");
		}
		if (p == null) {
			throw new IllegalArgumentException("Port cannot be null");
		}
		if (rxPackets == null) {
			throw new IllegalArgumentException("rxPackets cannot be null");
		}
		if (txPackets == null) {
			throw new IllegalArgumentException("txPackets cannot be null");
		}
		if (rxPkLoss == null) {
			throw new IllegalArgumentException("rxPkLoss cannot be null");
		}
		if (txPkLoss == null) {
			throw new IllegalArgumentException("txPkLosscannot be null");
		}
	
		return new SwitchPortPkLoss(d, p, rxPackets, txPackets, rxPkLoss, txPkLoss);
	}
	
	public DatapathId getSwitchId() {
		return id;
	}
	
	public OFPort getSwitchPort() {
		return pt;
	}
	
	public U64 getRxPackets() {
		return rxPackets;
	}
	public U64 getTxPackets() {
		return txPackets;
	}
	public U64 getTxPkLoss() {
		return txPkLoss;
	}
	public U64 getRxPkLoss() {
		return rxPkLoss;
	}
	
	public long getUpdateTime() {
		return time.getTime();
	}

	public long getStartTime_ns() {
		return starttime_ns;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((pt == null) ? 0 : pt.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SwitchPortPkLoss other = (SwitchPortPkLoss) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (pt == null) {
			if (other.pt != null)
				return false;
		} else if (!pt.equals(other.pt))
			return false;
		return true;
	}
}