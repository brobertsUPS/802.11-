package wifi;
import rf.RF;

import java.io.PrintWriter;
import java.util.concurrent.*;
import java.util.*;


/**
 * Threaded Receiver that continually watches the RF layer for incoming information
 * @author Brandon Roberts
 * @author Nate Olderman
 */
public class Receiver implements Runnable {
	private static final int BUFFER_SIZE_LIMIT = 4; //the limit to the size of the buffers
	private static final int SEQ_NUM_LIMIT = (1 << 12); //the sequence numbers should never hit 2^12

	private RF rf;
	private short ourMac;
	private LocalClock localClock;
	private PrintWriter output;

	private ArrayDeque<Packet> senderBuf;
	private ArrayBlockingQueue<Packet> receiverBuf;
	
	private HashMap<Short, Short> recvSeqNums; //expected seqNum for stuff we get from other hosts
	private HashMap<Short, Packet[]> outOfOrderTable; //packets that have a higher seqNum than we are expecting for the srcAddress
	

	/**
	 * Makes a new Receiver object that watches the RF layer for incoming information
	 * @param theRF the RF layer to receive from
	 * @param senderBuffer the queue of packets needing to be sent (use in Reciever to confirm ACK)
	 * @param receiverBuffer the que of received packets
	 * @param theMac our MAC address
	 * @param theLocalClock the local clock object
	 * @param outputWriter the output to write to
	 */
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer, short theMac, LocalClock theLocalClock, PrintWriter outputWriter){
		rf = theRF;
		senderBuf = senderBuffer;
		receiverBuf = receiverBuffer;
		ourMac = theMac;
		output = outputWriter;
		localClock = theLocalClock;

		recvSeqNums = new HashMap<Short, Short>();
		outOfOrderTable = new HashMap<Short, Packet[]>();
	}
	
	/**
	 * Begins waiting for the rf layer to receive and puts it in the receiverBuf
	 */
	public void run() {
		
		if(receiverBuf == null){
			localClock.setLastEvent(LocalClock.BAD_ADDRESS);//BAD_ADDRESS 	Pointer to a buffer or address was NULL
			if(localClock.getDebugOn())
				output.println("BAD ADDRESS");
		}
		
		while(true){
			Packet packet = new Packet(rf.receive());
			
			//---all conditions below are mutually exclusive, if one happens, none of the others happen---//
			//if packet is corrupt
			if(packet.checkIfCorrupt()){
				localClock.setLastEvent(LocalClock.UNSPECIFIED_ERROR);//UNSPECIFIED_ERROR 	General error code
				if(localClock.getDebugOn())
					output.println("CORRUPTED PACKET RECEIVED");
			}

			//if the packet is a beacon
			else if(packet.getFrameType() == 2){
				if(packet.getDestAddr() == -1 && localClock.getBeaconsOn() && checkBcastSeqNum(packet))//shares the same kind of seqNum check as Bcast
					localClock.updateClockOffset(packet);
			}

			//if the buffer is full
			else if(receiverBuf.size() >= 4 ){
				localClock.setLastEvent(LocalClock.INSUFFICIENT_BUFFER_SPACE);//INSUFFICIENT_BUFFER_SPACE 	Outgoing transmission rejected due to insufficient buffer space
				
				if(localClock.getDebugOn())
					output.println("INSUFFICIENT BUFFER SPACE");
			}

			//if the packet was sent to everyone (bcast)
			else if(packet.getDestAddr() == -1 && packet.getFrameType() == 0){
				if(checkBcastSeqNum(packet)){
					try{ 
						receiverBuf.put(packet);	//put up the broadcast no matter what
					} catch(InterruptedException e){
						System.err.println("Receiver interrupted!");
					}
				}
			}

			//if the destination was our mac address
			else if(packet.getDestAddr() == ourMac){
				if(packet.getFrameType() == 1){//if it is an ack
					if(packet.getSeqNum() == senderBuf.peek().getSeqNum())
						senderBuf.peek().setAsAcked();	//tell sender that that packet was ACKed
				}
				else if(packet.getFrameType() == 0)//else if it is normal data
					checkSeqNum(packet);
			}
		}
	}


	/**
	* Checks the sequence number on the packet, and does any necessary sequence number work
	* @param packet the packet whose sequence number it is checking
	*/
	private void checkSeqNum(Packet packet){
		short expectedSeqNum = getExpectedSeqNum(packet.getSrcAddr()); 

		//if we haven't seen this host yet
		if(expectedSeqNum == 0)
			outOfOrderTable.put(packet.getSrcAddr(), new Packet[BUFFER_SIZE_LIMIT*2]);//need double the allocated window space as can be sent at one time
		
		//if the sequence number is what we expect
		if(expectedSeqNum == packet.getSeqNum()){
			//put it in the reciever buf to be taken by the layer above
			try{ 
				receiverBuf.put(packet);
			} catch(InterruptedException e){
				System.err.println("Receiver interrupted!");
			}
		
			//make an ACK and add to the front of the senderBuf
			senderBuf.addFirst(new Packet((short)1, packet.getSeqNum(), packet.getSrcAddr(), packet.getDestAddr(), new byte[1])); 
			
			//checks to see if there are other packets that had higher sequence numbers that should be pushed to layer above
			checkOutOfOrderTable(packet, outOfOrderTable.get(packet.getSrcAddr()));
		}
		
		//if the recieved packet has a higher sequence number than what we expect
		else if(expectedSeqNum < packet.getSeqNum()){ 
			localClock.setLastEvent(LocalClock.UNSPECIFIED_ERROR);
			//doesn't print out error message if debug is on because we were supposed to print out the fact that a gap was detected whether or not debug was on
			output.println("Detected a gap in the sequence numbers on incoming data packets from host: " + packet.getSrcAddr());

			//get how far away this is from the expected sequence number for position in array (-1 because the expected packet doesn't have a spot in array)
			int displacement = packet.getSeqNum() - expectedSeqNum - 1;

			//if we are within the bounds of what we can hold onto
			if(displacement < BUFFER_SIZE_LIMIT * 2){
				Packet[] missingPackets = outOfOrderTable.get(packet.getSrcAddr());//get a pointer to make the next line readable
				//add the packet to the spot in the array
				missingPackets[displacement] = packet; 
			}
		}

		//otherwise it was for something we already got and the ACK got lost, so we have to resend ACK
		else{
			//--check the senderBuf if we were about to send an ACK for this--//
			//copy over the senderBuf so it can be looped through
			Packet[] senderQueue = new Packet[BUFFER_SIZE_LIMIT];
			senderBuf.toArray(senderQueue);

			boolean hadAnACK = false;
			for(int i = 0; i < senderQueue.length; i++){
				//if it is an ACK for the same packet
				if(senderQueue[i].getFrameType() == 1 && senderQueue[i].getSeqNum() == packet.getSeqNum() && senderQueue[i].getDestAddr() == packet.getSrcAddr())
					hadAnACK = true;
			}

			//make an ACK and add to the front of the senderBuf
			if(!hadAnACK)
				senderBuf.addFirst(new Packet((short)1, packet.getSeqNum(), packet.getSrcAddr(), packet.getDestAddr(), new byte[1])); 
		}
	}

	/**
	* Checks to see if we should accept a bcast (or beacon because they use the same method) based on the sequence number
	* @param packet the packet we are checking if we want to accept
	* @return true if we should accept the packet
	*/
	private boolean checkBcastSeqNum(Packet packet){
		//make sure the seq num is greater than or equal to expected
		if(packet.getSeqNum() >= getExpectedSeqNum((short)-1)){
			updateSeqNum(packet.getDestAddr(), packet.getSeqNum());
			return true;
		}
		return false;
	}

	/**
	* Updates the sequence number
	* @param address the address whose sequence numbers we are tracking
	* @param oldSeqNum the sequence number that needs updating
	*/
	private void updateSeqNum(short address, short oldSeqNum){
		if(oldSeqNum + 1 >= SEQ_NUM_LIMIT)
			recvSeqNums.put(address, (short)0);
		else	
			recvSeqNums.put(address, (short)(oldSeqNum + 1));	
	}

	/**
	* Gets the expected sequence number for the given source address
	* @param sourceAddress the source address to find the corresponding expected sequence number 
	* @return the expected sequence number
	*/
	private short getExpectedSeqNum(short sourceAddress){
		//check whether or not we have recieved from this address before
		if(!recvSeqNums.containsKey(sourceAddress)){
			recvSeqNums.put(sourceAddress, (short) 0); //assuming it starts at zero
			return 0;
		}
		else
			return recvSeqNums.get(sourceAddress).shortValue(); //getting what sequence number we expect
	}

	/**
	* Helper method that checks if there are packets with higher seqNums waiting in the queue that should be given to the layer above
	* it also then updates the expected seqNum
	* @param currentPacket the current packet to compare the out of order packets to
	* @param packets the queue of packets with higher seq nums
	*/
	private void checkOutOfOrderTable(Packet currentPacket, Packet[] packets){
		int i;//save so it can be used to update expected seq num below

		for(i = 0; i < packets.length; i++){ //go through everything in the queue
			if(packets[i] == null)//if this spot is a gap
				break;//break out of the loop!!!
			else{
				//give it to the layer above
				try{ 
					receiverBuf.put(packets[i]);
				} catch(InterruptedException e){
					System.err.println("Receiver interrupted!");
				}
			}
		}

		//advance items in the array so the first packet in the queue is at index 0
		//doesn't leave a spot for the gap because that is what we are expecting next
		outOfOrderTable.put(currentPacket.getSrcAddr(), Arrays.copyOfRange(packets, i+1, packets.length));
		
		//update expected seqNum to the current packet's sequence number + 1 (to update to the next expected) and then + i (to update how many packets were taken from waiting)
		updateSeqNum(currentPacket.getSrcAddr(), (short) (currentPacket.getSeqNum() + i));
	}
}