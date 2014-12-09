package wifi;
import rf.RF;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.*;


/**
 *
 * Threaded Receiver that continually watches the RF layer for incoming information
 * @author Brandon Roberts
 * @author Nate Olderman
 *
 */
public class Receiver implements Runnable {
	private RF rf;
	private short ourMac;
	private long[] clockOffset; //array with one item in it, used as a pointer
	private PrintWriter output;

	private ArrayDeque<Packet> senderBuf;
	private ArrayBlockingQueue<Packet> receiverBuf;
	
	private HashMap<Short, Short> recvSeqNums; 					//expected seqNum for stuff we get from other hosts
	private HashMap<Short, ArrayList<Packet>> outOfOrderTable;	//packets that have a higher seqNum than we are expecting for the srcAddress
	
	/**
	 * Makes a new Receiver object that watches the RF layer for incoming information
	 * @param theRF the RF layer to receive from
	 * @param senderBuffer the queue of packets needing to be sent (use in Reciever to confirm ACK)
	 * @param receiverBuffer the que of received packets
	 * @param currentClockOffset the offset from the time in rf.clock(), it is an array to be used as a pointer and shared with the sender
	 */
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer, short theMac, PrintWriter outputWriter, long[] currentClockOffset){
		rf = theRF;
		senderBuf = senderBuffer;
		receiverBuf = receiverBuffer;
		ourMac = theMac;
		output = outputWriter;
		clockOffset = currentClockOffset;

		recvSeqNums = new HashMap<Short, Short>();
		outOfOrderTable = new HashMap<Short, ArrayList<Packet>>();
	}
	
	/**
	 * Begins waiting for the rf layer to receive and puts it in the receiverBuf
	 */
	public void run() {
		while(true){
			try {
				Packet packet = new Packet(rf.receive()); 
				
				//if the packet is a beacon
				if(packet.getFrameType() == 2){
					updateClockOffset(packet);
				}
				//else if it's destination is our mac address
				else if(packet.getDestAddr() == ourMac){
					
					if(receiverBuf.size() == 4){ break; }////ignore -----NOTE: what is this??-----

					//if its an ack AND it has the same sequence number
					if((packet.getFrameType() == 1)  &&  (packet.getSeqNum() == senderBuf.peek().getSeqNum()))
						senderBuf.peek().setAsAcked();		//tell sender that that packet was ACKed
					
					//not an ack so recieve the data
					else
						checkSeqNums(packet);
				}				
			} catch (InterruptedException e) {
				System.err.println("Receiver interrupted!");
			}
		}
	}


	/*
	* Recieves 
	*
	*/
	private void checkSeqNums(Packet packet){
		short expectedSeqNum;

		//check whether or not we have recieved from this address before
		if(!recvSeqNums.containsKey(packet.getSrcAddr())){
			recvSeqNums.put(packet.getSrcAddr(), (short) 0); //assuming it starts at zero
			expectedSeqNum = 0;
			outOfOrderTable.put(packet.getSrcAddr(), new ArrayList<Packet>(4096));
		}
		else
			expectedSeqNum = recvSeqNums.get(packet.getSrcAddr()).shortValue(); //getting what sequence number we expect

		
		ArrayList<Packet> packets = outOfOrderTable.get(packet.getSrcAddr());//get the array for packets with higher than expected seq num

		//if the sequence number is what we expect
		if(expectedSeqNum == packet.getSeqNum()){
			receiverBuf.put(packet); //put it in the reciever buf to be taken by the layer above
			recvSeqNums.put(packet.getSrcAddr(), (short)(expectedSeqNum + 1)); //update the expected sequence number
			
			packet.makeIntoACK(); //to save time just make the same packet into an ACK
			senderBuf.addFirst(packet); //add the (now) ACK to the senderBuf to be sent
			
			//check if there are packets with higher seqNums waiting in the queue now that we found the expected seqNum
			if(!packets.isEmpty()){
				for(Packet queuedPacket : packets){ //go through everything in the queue, and give it to the layer above until it hits a sequence number gap (missing a packet)
					if(queuedPacket == null)//if its a gap, break out of loop
						break;

					receiverBuf.put(queuedPacket);
					recvSeqNums.put(queuedPacket.getSrcAddr(), (short)(expectedSeqNum + 1));//update expected seqNum
				}
			}
		}
		//if the recieved packet has a higher sequence number than what we expect
		else if(expectedSeqNum < packet.getSeqNum()){ 
			output.println("Detected a gap in the sequence nmbers on incoming data packets from host: " + packet.getSrcAddr());
			//packets.add(packet.getSeqNum() - expectedSeqNum - 1, packet);  				//**********************************THIS GETS DESTROYED AND WE NEVER KEPT THE PACKET IN THE TABLE
			outOfOrderTable.get(packet.getSrcAddr()).add(packet.getSeqNum() - expectedSeqNum - 1, packet);//adding the packet to the spot in the arraylist corresponding to the distance from the expected sequence number -1 to maintain starting at 0
		}
	}


	private void updateClockOffset(Packet packet){
		byte[] beaconTimeArray = packet.getDataBuf();
		long beaconTime = beaconTimeArray[beaconTimeArray.length-1];
			
		for(int i = beaconTimeArray.length - 2; i >= 0; i--){
			beaconTime = beaconTime << 8;
			beaconTime += beaconTimeArray[i];
		}

		long clockDifference = beaconTime - (clockOffset[0] + rf.clock());
		if(clockDifference > 0)
			clockOffset[0] += clockDifference;
	}
}