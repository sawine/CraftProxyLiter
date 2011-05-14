package com.raphfrk.craftproxyliter;

import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.raphfrk.protocol.KillableThread;
import com.raphfrk.protocol.Packet;
import com.raphfrk.protocol.ProtocolInputStream;
import com.raphfrk.protocol.ProtocolOutputStream;

public class UpstreamBridge extends KillableThread {

	final ProtocolInputStream in;
	final ProtocolOutputStream out;
	final PassthroughConnection ptc;
	final FairnessManager fm;


	UpstreamBridge(ProtocolInputStream in, ProtocolOutputStream out, PassthroughConnection ptc, FairnessManager fm) {

		this.in = in;
		this.out = out;
		this.ptc = ptc;
		this.fm = fm;

	}

	LinkedList<Byte> oldPacketIds = new LinkedList<Byte>();

	public void run() {

		Packet hashPacket = new Packet();
		hashPacket.buffer = new byte[2049*8];
		hashPacket.mask = 0xFFFFFFFF;
		hashPacket.start = 0;

		Packet packet = new Packet();
		Packet packetBackup = packet;
		long[] hashStore = new long[2048];
		
		boolean blankSent = !Globals.localCache();

		while(!killed()) {

			if((!blankSent) || (!ptc.connectionInfo.hashesToSend.isEmpty())) {

				blankSent = true;
				
				Long hash;

				ConcurrentLinkedQueue<Long> queue = ptc.connectionInfo.hashesToSend;
				ConcurrentHashMap<Long,Boolean> sent = ptc.connectionInfo.hashesSent;

				while(!killed() && !queue.isEmpty()) {
					int size = 0;
					hashPacket.end = 0;
					hashPacket.writeByte((byte)0x50);
					while(size < 2047 && (hash = queue.poll()) != null) {
						if(!sent.containsKey(hash)) {
							hashStore[size++] = hash;
							sent.put(hash, true);
						}
					}
					hashPacket.writeShort((short)(size * 8));
					for(int cnt=0;cnt<size;cnt++) {
						hashPacket.writeLong(hashStore[cnt]);
					}
					ptc.connectionInfo.saved.addAndGet(-(size*8 + 3));
					fm.addPacketToHighQueue(out, hashPacket, this);
				}
			}
			
			if(killed()) {
				continue;
			}

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
				
				boolean dontSend = false;
				if(packet.getByte(0) == 0x50) {
					
					int pos = 1;
					long hash;
					ConcurrentHashMap<Long,Boolean> hashes = ptc.connectionInfo.hashesReceived;
					
					short size = packet.getShort(pos);
					pos += 2;
					
					if(!ptc.connectionInfo.cacheInUse.getAndSet(true)) {
						ptc.printLogMessage("Cache requested by client");
					}
					
					for(int cnt=0;cnt<size;cnt++) {
						hash = packet.getLong(pos);
						hashes.put(hash,true);
					}
					dontSend = true;
				}
				
				oldPacketIds.add(packet.buffer[packet.start & packet.mask]);
				if(this.oldPacketIds.size() > 20) {
					oldPacketIds.remove();
				}

				if(!dontSend) {
					fm.addPacketToHighQueue(out, packet, this);
				}

			}
		}

		try {
			out.flush();
		} catch (IOException e) {
			ptc.printLogMessage("Unable to flush output stream");
		}

	}

}
