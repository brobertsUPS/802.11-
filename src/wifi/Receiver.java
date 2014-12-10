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
	
	private HashMap<Short, Short> recvSeqNums; //expected seqNum for stuff we get from other hosts
	private HashMap<Short, ArrayList<Packet>> outOfOrderTable; //packets that have a higher seqNum than we are expecting for the srcAddress
	

	/**
	 * Makes a new Receiver object that watches the RF layer for incoming information
	 * @param theRF the RF layer to receive from
	 * @param senderBuffer the queue of packets needing to be sent (use in Reciever to confirm ACK)
	 * @param receiverBuffer the que of received packets
	 * @param theMac our MAC address
	 * @param currentClockOffset the offset from the time in rf.clock(), it is an array to be used as a pointer and shared with the sender
	 * @param outputWriter the output to write to
	 */
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer, short theMac, long[] currentClockOffset, PrintWriter outputWriter){
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
			Packet packet = new Packet(rf.receive()); 
			
			//if the packet is a beacon
			if(packet.getFrameType() == 2){
				updateClockOffset(packet);
			}
			//else if it's destination is our mac address or -1 (-1 should be accepted by everyone)
			else if(packet.getDestAddr() == ourMac || packet.getDestAddr() == -1){
				
				if(receiverBuf.size() == 4){ break; }////ignore -----NOTE: what is this??-----
				
				//if its an ack AND it has the same sequence number
				if((packet.getFrameType() == 1) && !packet.checkIfCorrupt() && (packet.getSeqNum() == senderBuf.peek().getSeqNum()))
					senderBuf.peek().setAsAcked();		//tell sender that that packet was ACKed
				
				//not an ack so recieve the data
				else
					checkSeqNum(packet);
			}				
		}
	}


	/**
	* Checks the sequence number on the packet, and does any necessary sequence number work
	* @param packet - the packet whose sequence number it is checking
	*/
	private void checkSeqNum(Packet packet){
		short expectedSeqNum;

		//check whether or not we have recieved from this address before
		if(!recvSeqNums.containsKey(packet.getSrcAddr())){
			recvSeqNums.put(packet.getSrcAddr(), (short) 0); //assuming it starts at zero
			expectedSeqNum = 0;
			outOfOrderTable.put(packet.getSrcAddr(), new ArrayList<Packet>());
		}
		else
			expectedSeqNum = recvSeqNums.get(packet.getSrcAddr()).shortValue(); //getting what sequence number we expect

		//if the sequence number is what we expect
		if(expectedSeqNum == packet.getSeqNum()){
			//put it in the reciever buf to be taken by the layer above
			try{ 
				receiverBuf.put(packet);
			} catch(InterruptedException e){
				System.err.println("Receiver interrupted!");
			}

			recvSeqNums.put(packet.getSrcAddr(), (short)(expectedSeqNum + 1)); //update the expected sequence number
		
			senderBuf.addFirst(new Packet((short)1, packet.getSeqNum(), packet.getSrcAddr(), packet.getDestAddr(), new byte[1])); //make and add ACK to the senderBuf to be sent
			
			checkOutOfOrderTable(outOfOrderTable.get(packet.getSrcAddr())); //checks to see if there are other packets that had higher sequence numbers
		}
		
		//if the recieved packet has a higher sequence number than what we expect
		else if(expectedSeqNum < packet.getSeqNum()){ 
			output.println("Detected a gap in the sequence nmbers on incoming data packets from host: " + packet.getSrcAddr());
			outOfOrderTable.get(packet.getSrcAddr()).add(packet.getSeqNum() - expectedSeqNum - 1, packet);//adding the packet to the spot in the arraylist corresponding to the distance from the expected sequence number -1 to maintain starting at 0
		}
	}


	/**
	* Helper method that checks if there are packets with higher seqNums waiting in the queue that should be given to the layer above
	* @param packets - the queue of packets with higher seq nums
	*/
	private void checkOutOfOrderTable(ArrayList<Packet> packets){
		if(!packets.isEmpty()){
			for(int i = 0; i < packets.size(); i++){ //go through everything in the queue
				if(packets.get(i) == null){//if this spot is a gap
					ArrayList<Packet> temp = new ArrayList<Packet>(packets.subList(i+1, packets.size()));
					outOfOrderTable.put(packets.get(i).getSrcAddr(), temp); //push up the items in the arrayList
					recvSeqNums.put(packets.get(i).getSrcAddr(), (short)i);//update expected seqNum
					break;
				}

				//give it to the layer above
				try{ 
					receiverBuf.put(packets.get(i));
				} catch(InterruptedException e){
					System.err.println("Receiver interrupted!");
				}
			}
		}
	}

	/**
	* Updates the offset for the clock based on the give beacon packet's time
	* @param packet - the beacon packet that has the time to update to
	*/
	private void updateClockOffset(Packet packet){
		//get the time in a byte array from the data buf
		byte[] timeArray = packet.getDataBuf();

		//start out the time variable when initializing, then copy the rest of it over in the loop
		long otherHostTime = timeArray[timeArray.length-1];
		for(int i = timeArray.length - 2; i >= 0; i--){
			otherHostTime = otherHostTime << 8;
			otherHostTime += timeArray[i];
		}

		//get the difference in the clocks
		long clockDifference = otherHostTime - (clockOffset[0] + rf.clock());
		if(clockDifference > 0)//if the other host is ahead of us in time, advance our time to match
			clockOffset[0] += clockDifference;
	}
}