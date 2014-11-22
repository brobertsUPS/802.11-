package wifi;

/**
 * The GUI client application can interact with any project that implements
 * this interface.  The routines listed here are not exactly those that an
 * 802.11~ implementation is supposed to provide (see {@link Dot11Interface}
 * for those), but have been designed for compatibility with either a C++
 * or Java-based implementation.  If you choose to write your project in Java,
 * you should use the {@link JavaGUIAdapter} class to implement this
 * interface.  It wraps some code around the basic 802.11~ functionality to
 * support the routines in the interface.  (Use {@link CppGUIAdapter} if you're
 * implementing your project in C++.)
 * 
 * @author richards
 */
public interface GUIClientInterface {
   /** Returns an array of MAC addresses that will be assigned to buttons in the GUI client. */
   public short[] getDefaultAddrs();

   /**
    * Create an instance of the 802.11~ layer and set up the output streams.
    * @param MACaddr  The 802.11~ MAC address of this host.
    * @return Result code (0 if all goes well).
    */
   public int initializeLinkLayer(short MACaddr);

   /**
    * This is a wrapper around the 802.11~ recv() that watches for incoming packets, then
    * prepends the srcAddr to the incoming data and returns it as a byte array.
    */
   public byte[] watchForIncomingData();

   /**
    * Called by the GUI client when data is to be sent.
    * @param dest    MAC address of destination machine
    * @param payload Data to be sent
    * @return Number of bytes sent
    */
   public int sendOutgoingData(short dest, byte[] payload);

   /**
    * Called periodically by the GUI client to see if the 802.11~ layer has produced any
    * output that should be displayed in the client's text window.
    * @return  Array of characters to be displayed
    */
   public byte[] pollForStreamOutput();
   
   /**
    * The GUI calls this when the user asks to pass command info to the 802.11~ layer.
    * @param command  Specifies the command to send
    * @param value    The value passed with the command
    */
   public int sendCommand(int command, int value);

}