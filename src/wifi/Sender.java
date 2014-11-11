package wifi;

import java.util.ArrayDeque;

import rf.RF;

/**
 * 
 * @author Brandon Roberts
 *
 */
public class Sender implements Runnable{

	private RF rf;
	private long sendTime;
	private ArrayDeque<Packet> packetBuf;
	
	public Sender(RF theRF, ArrayDeque<Packet> packetBuffer){
		rf = theRF;
		packetBuf = packetBuffer;
	}
	
	/**
	 * 
	 */
	public void run() {
		
		while(rf.inUse()){
			try{
				wait(10);
			}catch(InterruptedException e){
				System.err.println("Sender interrupted!");
			}
		}
		rf.transmit(packetBuf.poll().toBytes());
		
	}

}
