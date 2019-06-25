package net.floodlightcontroller.statistics;

import java.util.Date;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import net.floodlightcontroller.statistics.web.SwitchPortBandwidthSerializer;

@JsonSerialize(using=SwitchPortBandwidthSerializer.class)
public class SwitchPortBandwidth {
	private DatapathId id;//交换机ID
	private OFPort pt;//交换机端口
	private U64 speed;//链路速率，应该是交换机端口支持的最大链路速率
	
	//带宽是，单位时间内能够传输的比特数
	private U64 rx;//receive	实际接收带宽 bit/s,这里的单位应该是比特每秒
	private U64 tx;//transport	实际发送带宽
	private Date time;//更新时间
	private long starttime_ns;//开始时间，精确到纳秒
	private U64 rxValue;//先前接收的字节总数
	private U64 txValue;//先前发送的字节总数
	
	private SwitchPortBandwidth() {}
	private SwitchPortBandwidth(DatapathId d, OFPort p, U64 s, U64 rx, U64 tx, U64 rxValue, U64 txValue) {
		id = d;
		pt = p;
		speed = s;
		this.rx = rx;
		this.tx = tx;
		time = new Date();
		starttime_ns = System.nanoTime();
		this.rxValue = rxValue;
		this.txValue = txValue;
	}
	
	public static SwitchPortBandwidth of(DatapathId d, OFPort p, U64 s, U64 rx, U64 tx, U64 rxValue, U64 txValue) {
		if (d == null) {
			throw new IllegalArgumentException("Datapath ID cannot be null");
		}
		if (p == null) {
			throw new IllegalArgumentException("Port cannot be null");
		}
		if (s == null) {
			throw new IllegalArgumentException("Link speed cannot be null");
		}
		if (rx == null) {
			throw new IllegalArgumentException("RX bandwidth cannot be null");
		}
		if (tx == null) {
			throw new IllegalArgumentException("TX bandwidth cannot be null");
		}
		if (rxValue == null) {
			throw new IllegalArgumentException("RX value cannot be null");
		}
		if (txValue == null) {
			throw new IllegalArgumentException("TX value cannot be null");
		}
		return new SwitchPortBandwidth(d, p, s, rx, tx, rxValue, txValue);
	}
	
	public DatapathId getSwitchId() {
		return id;
	}
	
	public OFPort getSwitchPort() {
		return pt;
	}
	
	public U64 getLinkSpeedBitsPerSec() {
		return speed;
	}
	
	public U64 getBitsPerSecondRx() {
		return rx;
	}
	
	public U64 getBitsPerSecondTx() {
		return tx;
	}
	
	protected U64 getPriorByteValueRx() {
		return rxValue;
	}
	
	protected U64 getPriorByteValueTx() {
		return txValue;
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
		SwitchPortBandwidth other = (SwitchPortBandwidth) obj;
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