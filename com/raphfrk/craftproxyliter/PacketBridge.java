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

import java.io.EOFException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

import com.raphfrk.protocol.KillableThread;
import com.raphfrk.protocol.Packet;
import com.raphfrk.protocol.ProtocolInputStream;
import com.raphfrk.protocol.ProtocolOutputStream;

public class PacketBridge extends KillableThread {

	final ProtocolInputStream in;
	final ProtocolOutputStream out;
	final PassthroughConnection ptc;
	final FairnessManager fm;
	
	PacketBridge(ProtocolInputStream in, ProtocolOutputStream out, PassthroughConnection ptc, FairnessManager fm) {

		this.in = in;
		this.out = out;
		this.ptc = ptc;
		this.fm = fm;

	}

	LinkedList<Byte> oldPacketIds = new LinkedList<Byte>();

	public void run() {

		Packet packet = new Packet();
		Packet packetBackup = packet;

		while(!killed()) {

			try {
				packet = in.getPacket(packet);
				if(packet == null) {
					ptc.printLogMessage("Timeout");
					kill();
					continue;
				}
			} catch (EOFException e) {
				ptc.printLogMessage("EOF reached");
				kill();
				continue;
			} catch (IOException e) {
				System.out.println("ERROR");
			}
			if(packet == null) {
				if(!killed()) {
					kill();
					ptc.printLogMessage(packetBackup + " Unable to read packet");
					ptc.printLogMessage("Packets: " + oldPacketIds);
				}
				continue;
			}

			if(packet.start < packet.end) {
				oldPacketIds.add(packet.buffer[packet.start & packet.mask]);
				if(this.oldPacketIds.size() > 20) {
					oldPacketIds.remove();
				}
			}
			
			try {
				fm.addPacketToHighQueue(out, packet, this);
			} catch (IOException ioe) {
				kill();
				continue;
			}

		}

		try {
			out.flush();
		} catch (IOException e) {
			ptc.printLogMessage("Unable to flush output stream");
		}

	}

}
