/*******************************************************************************
 * Copyright (C) 2012 Raphfrk
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package com.raphfrk.craftproxyliter;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

import com.raphfrk.protocol.Packet;
import com.raphfrk.protocol.Packet01Login;
import com.raphfrk.protocol.Packet02Handshake;
import com.raphfrk.protocol.PacketFFKick;

public class LoginManager {
	
	private static long MAGIC_SEED = 0x0123456789ABCDEFL;

	public static String getUsername(LocalSocket clientSocket, ConnectionInfo info, PassthroughConnection ptc, String pingHostname, Integer pingPort) {
		Packet packet = new Packet();

		try {
			boolean receivedLoginPacket;
			do {
				receivedLoginPacket = true;
				packet = clientSocket.pin.getPacket(packet);
				if(packet == null) {
					return "Client didn't send handshake packet";
				}
				if ((packet.getByte(0) & 0xFF) == 0xFA) {
					info.loginCustomPackets.add(packet.clone(null));
					packet = new Packet();
					receivedLoginPacket = false;
				}
			} while (!receivedLoginPacket);
		} catch (EOFException eof) {
			return "Client closed connection before sending handshake";
		} catch (IOException ioe) {
			return "IO Error reading client handshake";
		}

		if(packet.getByte(0) == 0x02) {
			Packet02Handshake CtSHandshake = new Packet02Handshake(packet);
			info.setUsername(CtSHandshake.getUsernameSplit());
			info.setUsernameRaw(CtSHandshake.getUsername());
		} else if (packet.getByte(0) == 0x52){
			Packet52ProxyLogin proxyLogin = new Packet52ProxyLogin(packet);
			info.setUsername(proxyLogin.getUsernameSplit());
			info.setUsernameRaw(proxyLogin.getUsername());
			info.setHostname(proxyLogin.getHostname());
			info.forwardConnection = true;
			ptc.printLogMessage("Proxy to proxy connection received, forwarding to " + ptc.connectionInfo.getHostname());
		} else if ((packet.getByte(0) & 0xFF) == 0xFE) {
			long currentTime = System.currentTimeMillis();
			String address = ptc.IPAddress;
			Long lastPing = ptc.proxyListener.lastPing.get(address);
			ptc.proxyListener.lastPing.put(address, currentTime);
			//if (lastPing == null || lastPing + 5000 < currentTime) {
				Long oldLastLogin = ptc.proxyListener.lastLoginOld.get(address);
				if (oldLastLogin == null) {
					ptc.proxyListener.lastLogin.remove(address);
				} else {
					ptc.proxyListener.lastLogin.put(address, oldLastLogin);
				}
			//}
			if (pingPort == null || pingHostname == null) {
				return "Server offline";
			} else {
				ptc.printLogMessage("Forwarding ping");
				Socket serverSocket;
				try {
					serverSocket = new Socket(pingHostname, pingPort);
				} catch (IOException ioe) {
					return "Unable to connect";
				}
				LocalSocket serverLocalSocket;
				try {
					serverSocket.setSoTimeout(1000);
					serverLocalSocket = new LocalSocket(serverSocket, ptc, Globals.getMaxWorldHeight());
				} catch (IOException ioe) {
					return "Unable to connect";
				}
				try {
					serverLocalSocket.pout.sendPacket(packet);
				} catch (IOException e) {
					serverLocalSocket.closeSocket(ptc);
					return "Send ping failure";
				}
				Packet recv = new Packet();
				try {
					recv = serverLocalSocket.pin.getPacket(recv);
				} catch (IOException e) {
					serverLocalSocket.closeSocket(ptc);
					return "Receive ping failure";
				}
				serverLocalSocket.closeSocket(ptc);
				if ((recv.getByte(0) & 0xFF) == 0xFF) {
					PacketFFKick kick = new PacketFFKick(recv);
					return kick.getString16(1);
				} else {
					return "Bad ping kick packet";
				}
				
			}
		} else {
			return "Unknown login packet id " + packet.getByte(0);
		}

		return null;

	}

	public static String bridgeLogin(LocalSocket clientSocket, LocalSocket serverSocket, ConnectionInfo info, PassthroughConnection ptc, boolean reconnect, String fullHostname) {

		Packet packet = new Packet();

		Packet CtSHandshake;
		
		String password = Globals.getPassword();
		
		if(fullHostname == null || password == null) {
			if(fullHostname != null) {
				ptc.printLogMessage("WARNING: attempting to log into another proxy which has authentication enabled but password has not been set");
			}
			ptc.printLogMessage("Connecting using proxy to server connection format");
			CtSHandshake = new Packet02Handshake(info.getUsernameRaw());
		} else {
			ptc.printLogMessage("Connecting using proxy to proxy connection format");
			CtSHandshake = new Packet52ProxyLogin("", fullHostname, info.getUsernameRaw());
		}

		try {
			if(serverSocket.pout.sendPacket(CtSHandshake) == null) {
				return "Server didn't accept handshake packet";
			}
		} catch (EOFException eof) {
			return "Server closed connection before accepting handshake";
		} catch (IOException ioe) {
			return "IO Error sending client handshake to server";
		}

		try {
			packet = serverSocket.pin.getPacket(packet);
			if(packet == null) {
				return "Server didn't send handshake packet";
			}
		} catch (EOFException eof) {
			return "Server closed connection before sending handshake";
		} catch (IOException ioe) {
			return "IO Error reading server handshake";
		}

		Packet02Handshake StCHandshake = new Packet02Handshake(packet);

		String hash = StCHandshake.getUsername();
		
		if(fullHostname != null) {
			if(password == null) {
				ptc.printLogMessage("WARNING: attempting to log into another proxy which has authentication enabled but password has not been set");
			} else {
				String confirmCode = sha1Hash(password + hash);
				Packet code = new Packet52ProxyLogin(confirmCode, info.getHostname(), info.getUsernameRaw());
				ptc.printLogMessage("Sent 0x52 packet");
				try {
					if(serverSocket.pout.sendPacket(code) == null) {
						return "Server refused password packet";
					} 
				} catch (EOFException eof) {
					return "Server closed connection before accepting password packet";
				} catch (IOException ioe) {
					return "IO Error sending password packet";
				}
			}
		}

		String expectedCode = null;
		if(Globals.isAuth()) {
			hash = getHashString();
			StCHandshake = new Packet02Handshake(hash);
			expectedCode = sha1Hash(password + hash);
		}
		
		boolean passwordAccepted = false;

		if(!reconnect) {
			try {
				if(clientSocket.pout.sendPacket(StCHandshake) == null) {
					return "Client didn't accept handshake packet";
				}
			} catch (EOFException eof) {
				return "Client closed connection before accepting handshake";
			} catch (IOException ioe) {
				return "IO Error sending server handshake";
			}
			
			try {
				packet = clientSocket.pin.getPacket(packet, 10000);
				if (packet == null) {
					return "Malformed Login packet, server/client version mismatch?";
				} else if (packet.getByte(0) != 1 && packet.getByte(0) != 0x52) {
					return "Client didn't send login packet";
				}
				if (packet.getByte(0) == 1) {
					packet = new Packet01Login(packet);
					info.clientVersion = packet.getInt(1);
					//info.craftProxyLogin = ((Packet01Login)packet).getSeed() == MAGIC_SEED;
				}
				//((Packet01Login)packet).setSeed(MAGIC_SEED);
			} catch (EOFException eof) {
				return "Client closed connection before sending login";
			} catch (IOException ioe) {
				return "IO Error reading client login";
			}
			
			if(packet.getByte(0) == 0x52) {
				Packet52ProxyLogin proxyLogin = new Packet52ProxyLogin(packet);
				if(proxyLogin.getCode().equals(expectedCode)) {
					ptc.printLogMessage("Password accepted");
					passwordAccepted = true;
					try {
						packet = clientSocket.pin.getPacket(packet);
						if(packet == null) {
							return "Client didn't send login packet";
						}
						info.clientVersion = packet.getInt(1);
					} catch (EOFException eof) {
						return "Client closed connection before sending login";
					} catch (IOException ioe) {
						return "IO Error reading client login";
					}
				} else {
					ptc.printLogMessage("Expected: " + expectedCode);
					ptc.printLogMessage("Received: " + proxyLogin.getCode());
					return "Attemped password login failed";
				}
			}
			
		} else {
			String username = info.getUsername();
			packet = new Packet(200);
			packet.writeByte((byte)0x01);
			packet.writeInt(info.clientVersion);
			packet.writeString16(username.substring(0,Math.min(128, username.length())));
			packet.writeLong(0);
			packet.writeString16("");
			packet.writeInt(0);
			packet.writeByte((byte)0);
			packet.writeByte((byte)0);
			packet.writeByte((byte)0);
			packet.writeByte((byte)0);	
		}

		Packet01Login CtSLogin = new Packet01Login(packet);

		try {
			if(serverSocket.pout.sendPacket(CtSLogin) == null) {
				return "Server didn't accept login packet";
			}
		} catch (EOFException eof) {
			return "Server closed connection before accepting login";
		} catch (IOException ioe) {
			return "IO Error sending client login to server";
		}

		try {
			packet = serverSocket.pin.getPacket(packet);
			if(packet == null) {
				return "Server didn't send login packet";
			}
		} catch (EOFException eof) {
			return "Server closed connection before sending login";
		} catch (IOException ioe) {
			return "IO Error reading server login";
		}

		if(!passwordAccepted && !reconnect && Globals.isAuth()) {
			if(!authenticate(ptc.connectionInfo.getUsername(), hash, ptc)) {
				return "Authentication failed";
			}
		}

		Packet01Login StCLogin = new Packet01Login(packet);	

		info.serverPlayerId = StCLogin.getVersion();
		info.loginDimension = StCLogin.getDimension();
		info.loginUnknownRespawn = StCLogin.getUnknown();
		info.loginCreative = (byte)StCLogin.getMode();
		info.levelType = StCLogin.getLevelType();
		
		if(!reconnect) {
			info.clientPlayerId = StCLogin.getVersion();
			try {
				if(clientSocket.pout.sendPacket(StCLogin) == null) {
					return "Client didn't accept login packet";
				}
			} catch (EOFException eof) {
				return "Client closed connection before accepting login";
			} catch (IOException ioe) {
				return "IO Error sending server login";
			}
		}

		return null;

	}

	static SecureRandom hashGenerator = new SecureRandom();

	static String getHashString() {
		long hashLong;
		synchronized( hashGenerator ) {
			hashLong = hashGenerator.nextLong() & 0x7FFFFFFFFFFFFFFFL;
		}

		return Long.toHexString(hashLong);
	}

	static boolean authenticate( String username , String hashString, PassthroughConnection ptc )  {

		for (int i = 0; i < 5; i++) {
			try {
				String encodedUsername =  URLEncoder.encode(username, "UTF-8");
				String encodedHashString =  URLEncoder.encode(hashString, "UTF-8");
				String authURLString = new String( "http://www.minecraft.net/game/checkserver.jsp?user=" + encodedUsername + "&serverId=" + encodedHashString);
				if(!Globals.isQuiet()) {
					ptc.printLogMessage("Authing with " + authURLString);
				}
				URL minecraft = new URL(authURLString);
				BufferedReader in = new BufferedReader(new InputStreamReader(minecraft.openStream()));

				String reply = in.readLine();

				if( Globals.isInfo() ) {
					ptc.printLogMessage("Server Response: " + reply );
				}

				in.close();

				if( reply != null && reply.equals("YES")) {

					if(!Globals.isQuiet()) {
						ptc.printLogMessage("Auth successful");
					}
					return true;
				}
			} catch (MalformedURLException mue) {
				ptc.printLogMessage("Auth URL error");
				return false;
			} catch (IOException ioe) {
				if (i < 5) {
					ptc.printLogMessage("Problem connecting to auth server - trying again");
				} else {
					ptc.printLogMessage("Problem connecting to auth server");
					return false;
				}
			}
		}

		return false;
	}

	static String sha1Hash( String inputString ) {

		try {
			MessageDigest md = MessageDigest.getInstance("SHA");
			md.reset();

			md.update(inputString.getBytes("utf-8"));

			BigInteger bigInt = new BigInteger( md.digest() );

			return bigInt.toString( 16 ) ;

		} catch (Exception ioe) {
			return "hash error";
		}

	}

}
