package wifi;

import rf.RF;

/**
 * A class to represent an 802.11~ clock.
 * @author Nate Olderman
 * @author Brandon Roberts
 */
public class LocalClock{

	/**
	* Initial value if 802_init is successful
	*/
	public static final int SUCCESS = 1;

	/**
	* General error code
	*/
	public static final int UNSPECIFIED_ERROR = 2;

	/**
	* Attempt to initialize RF layer failed
	*/
	public static final int RF_INIT_FAILED = 3;

	/**
	* Last transmission was acknowledged
	*/
	public static final int TX_DELIVERED = 4;

	/**
	* Last transmission was abandoned after unsuccessful delivery attempts
	*/
	public static final int TX_FAILED = 5;
	
	/**
	* Buffer size was negative
	*/
	public static final int BAD_BUF_SIZE= 6;
	
	/**
	* Pointer to a buffer or address was NULL
	*/
	public static final int BAD_ADDRESS = 7;

	/**
	* Illegal MAC address was specified
	*/
	public static final int BAD_MAC_ADDRESS = 8;
	
	/**
	* One or more arguments are invalid
	*/
	public static final int ILLEGAL_ARGUMENT = 9;
	
	/**
	* Outgoing transmission rejected due to insufficient buffer space
	*/
	public static final int INSUFFICIENT_BUFFER_SPACE = 10;

	
	//these values were estimated averaging the results of:
	//10 tests using a Macbook pro with a Intel i7 processor running at 2.3 GHz (sending to the Gateway)
	//10 tests using a Gateway with a AMD A8 processor running at 1.9 GHz (sending to the Mac)
	//5 tests using the MAC (as the sender) and one of the lab machines (advance computing lab) (as receiver)
	private static final long CREATE_BEACON_OFFSET = 1425; //Average time to package a beacon and send it in milliseconds
	private static final long PROCESS_BEACON_OFFSET = 0; //Averaged as .02 milliseconds which was rounded down to zero
	private static final int ACK_TIMEOUT_VALUE = RF.aSlotTime + 4629; //after 15 tests we averaged 3629 ms

	private static final int DIFS = RF.aSIFSTime + (2 * RF.aSlotTime);

	private RF rf;
	
	private long clockOffset; //the offset between the local rf.clocks time and the advanced time calculated from received beacons
	
	private boolean beaconsOn; //whether or not beacons are turned on
	private double beaconInterval; //the length of time between sending beacons
	private long lastBeaconTime; //the time of the last beacon sent

	private boolean slotSelectionFixed; //true if the slot selection is fixed
	private int backoffCount;
	private int windowSize;

	private long startACKWait; //the start time of waiting for an ACK
	
	private int currentStatus; //whichever one of the above status codes happened the most recently

	private boolean debugOn; //whether or not debug is turned on

	/**
	* Creates a new LocalClock with a given RF layer
	* @param theRF the RF layer for the local clock's time to be based off of
	*/
	public LocalClock(RF theRF){
		rf = theRF;
		
		//initialize global variables
		clockOffset = 0;
		beaconInterval = 3000; //default should be 3 seconds
		lastBeaconTime = 0;
		slotSelectionFixed = false; //defaults to random slot selection
		beaconsOn = true;
		debugOn = false;
		backoffCount = 0;
		windowSize = 1;
		currentStatus = 0;
	}


	/**
	* Rounds up current time to the nearest 50ms boundary and adds to DIFS to get time to wait
	* (synchronized not needed here because it is not setting or getting any values that change)
	* @return rounded up DIFS wait time
	*/
	public long roundedUpDIFS(){
		return DIFS + (50 - rf.clock()%50);
	}


	/**
	* Calculates the time for a beacon
	* @return the beacon time in bytes or null if the beacon interval has not passed
	*/
	public synchronized byte[] calcBeaconTime(){
		//offset isn't used here in interval calculation 
		//because when these two are subtracted it would get negated anyway
		if(beaconsOn && rf.clock() - lastBeaconTime >= beaconInterval){
			lastBeaconTime = rf.clock();//update lastbeacontime for use here as well
			//System.out.println(lastBeaconTime);

			//make a data buffer with the current clock time
			long beaconTime = lastBeaconTime + clockOffset + CREATE_BEACON_OFFSET;
			
			byte[] beaconTimeArray = new byte[8]; //8 bytes for the beacon time
			for(int i = beaconTimeArray.length - 1; i >= 0; i--){
				beaconTimeArray[i] = (byte)(beaconTime & 0xFF);
				beaconTime = beaconTime >>> 8;
			}
			return beaconTimeArray;
		}
		return null;//otherwise it isn't ready to send beacon
	}

	/**
	* Updates the offset for the clock based on the give beacon packet's time
	* @param packet the beacon packet that has the time to update to
	*/
	public synchronized void updateClockOffset(Packet packet){
		//get the time in a byte array from the data buf
		byte[] timeArray = packet.getDataBuf();

		//start out the time variable when initializing, then copy the rest of it over in the loop
		long otherHostTime = (timeArray[0] & 0xFF);
		for(int i = 1; i < timeArray.length; i++){
			otherHostTime = otherHostTime << 8;
			otherHostTime += (timeArray[i] & 0xFF);
		}

		//get the difference in the clocks
		long clockDifference = otherHostTime + PROCESS_BEACON_OFFSET - getLocalTime();
		if(clockDifference > 0)//if the other host is ahead of us in time, advance our time to match
			clockOffset += clockDifference;
	}

	/**
	* Starts an ACK timer
	*/
	public synchronized void startACKTimer(){
		startACKWait = rf.clock();
	}

	/**
	* Checks if the ACK timer had timed out
	* @return true the ACK timer had timed otu
	*/
	public synchronized boolean checkACKTimeout(){
		return (rf.clock() - startACKWait >= ACK_TIMEOUT_VALUE);
	}


//---------------------------------------------------------------------------------------------------//
//---------------------------------------- Getters --------------------------------------------------//
//---------------------------------------------------------------------------------------------------//

	/**
	* Gets if the slot selection is fixed
	* @return true if the slot selection is fixed
	*/
	public synchronized boolean getSlotSelectionFixed(){
		return slotSelectionFixed;
	}

	/**
	* Gets the beacon interval
	* @return the beacon interval
	*/
	public synchronized double getBeaconInterval(){
		return beaconInterval;
	}
	
	/**
	 * Determines if beacons are turned on
	 * @return true if beacons are on
	 */
	public synchronized boolean getBeaconsOn(){
		return beaconsOn;
	}
	
	/**
	 * Determines if debug is turned on
	 * @return true if debus is on
	 */
	public synchronized boolean getDebugOn(){
		return debugOn;
	}
	
	/**
	 * Determines what the backoff count is currently at
	 * @return the size of the backoff count
	 */
	public synchronized int getBackoffCount(){
		return backoffCount;
	}
	
	/**
	 * Determines what the collission window is currently at
	 * @return the size of the collision window
	 */
	public synchronized int getCollisionWindow(){
		return windowSize;
	}
	
	/**
	 * Determines the currentStatus
	 * @return the currentStatus
	 */
	public synchronized int getLastEvent(){
		return currentStatus;
	}

	/**
	 * Returns the current clock offset
	 * @return the clock offset
	 */
	public synchronized long getLocalTime(){
		return clockOffset + rf.clock();
	}


//---------------------------------------------------------------------------------------------------//
//---------------------------------------- Setters --------------------------------------------------//
//---------------------------------------------------------------------------------------------------//

	/*
	 * Turns the beacons on
	 */
	public synchronized void setBeaconsOn(){
			beaconsOn = true;
	}

	/**
	* Set whether or not the slot selection is fixed
	* @param slotCommand the command should be 0 for random slot selection or anything else for fixed
	*/
	public synchronized void setSlotSelectionFixed(int slotCommand){
		if(slotCommand == 0)
			slotSelectionFixed = false;
		else
			slotSelectionFixed = true;
	}

	/**
	* Set the beacon interval
	* @param theBeaconInterval the beacon interval
	*/
	public synchronized void setBeaconInterval(double theBeaconInterval){
		if(theBeaconInterval == -1)
			beaconsOn = false;
		else
			beaconInterval = theBeaconInterval * 1000;
	}
	
	/**
	* Sets whether or not debug is on
	* @param debug should be 0 to turn debug off, or anything else to turn it on
	*/
	public synchronized void setDebug(int debug){
		if(debug == 0)
			debugOn = false;
		else
			debugOn = true;
	}
	
	/**
	 * Sets the backoff count
	 * @param backoff the backoff count to set it to
	 */
	public synchronized void setBackoffCount(int backoff){
		backoffCount = backoff;
	}
	
	/**
	 * Sets the collision window size
	 * @param collisionWindow the size to set it to
	 */
	public synchronized void setCollisionWindow(int collisionWindow){
		windowSize = collisionWindow;
	}
	
	/**
	 * Updates the currentStatus of the program
	 * @param newStatus
	 */
	public synchronized void setLastEvent(int newStatus){
		currentStatus = newStatus;
	}
}