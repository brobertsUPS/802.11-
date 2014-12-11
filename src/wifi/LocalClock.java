package wifi;

import rf.RF;

/**
 * A class to represent an 802.11~ clock.
 * @author Nate Olderman
 * @author Brandon Roberts
 */
public class LocalClock{
	private static final int DIFS = RF.aSIFSTime + (2 * RF.aSlotTime);
	private static final int ACK_TIMEOUT_VALUE = RF.aSlotTime + 2129; //after 10 tests we averaged 2129 ms

	private RF rf;
	
	private long clockOffset;
	
	private boolean beaconsOn; //whether or not beacons are turned on
	private double beaconInterval;
	private long lastBeaconTime;

	private boolean slotSelectionFixed; //true if the slot selection is fixed

	private long startACKWait;


	/**
	* Creates a new LocalClock with a given RF layer
	* @param theRF the RF layer for the local clock's time to be based off of
	*/
	public LocalClock(RF theRF){
		rf = theRF;

		clockOffset = 0;
		beaconInterval = 3000; //default should be 3 seconds
		lastBeaconTime = 0;
		slotSelectionFixed = false; //defaults to random slot selection
		beaconsOn = true;
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

			//make a data buffer with the current clock time
			long beaconTime = lastBeaconTime + clockOffset;
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
		long otherHostTime = timeArray[timeArray.length-1];
		for(int i = timeArray.length - 2; i >= 0; i--){
			otherHostTime = otherHostTime << 8;
			otherHostTime += timeArray[i];
		}

		//get the difference in the clocks
		long clockDifference = otherHostTime - (clockOffset + rf.clock());
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



//---------------------------------------------------------------------------------------------------//
//---------------------------------------- Setters --------------------------------------------------//
//---------------------------------------------------------------------------------------------------//

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
			beaconInterval = theBeaconInterval;
	}

}