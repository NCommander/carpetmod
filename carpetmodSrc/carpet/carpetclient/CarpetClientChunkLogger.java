package carpet.carpetclient;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import carpet.helpers.StackTraceDeobfuscator;

public class CarpetClientChunkLogger{
	
	public static CarpetClientChunkLogger logger = new CarpetClientChunkLogger();
	
	// TODO implement enable/disable
	boolean enabled = true;
	// TODO only initialize stackTraces if enabled, delete when disabled
	boolean allowStacktraces = true;
	StackTraces stackTraces;
	ChunkLoggerSerializer clients;

	public static enum Event {
		ERROR,
		UNLOADING,
		LOADING,
		PLAYER_ENTERS,
		PLAYER_LEAVES,
		QUEUE_UNLOAD,
		CANCEL_UNLOAD,
		UNQUEUE_UNLOAD,
		GENERATING,
		POPULATING,
		GENERATING_STRUCTURES;
	}

	class ChunkLogCoords {
		int chunkX;
		int chunkZ;
		int chunkDimension;
		
		ChunkLogCoords(int x, int z, int d){
			chunkX = x;
			chunkZ = z;
			chunkDimension = d;
		}
		
		@Override
		public boolean equals(Object oo) {
			if(oo instanceof ChunkLogCoords){
				ChunkLogCoords o = (ChunkLogCoords) oo;
				return this.chunkX == o.chunkX && this.chunkZ == o.chunkZ && this.chunkDimension == o.chunkDimension;
			}
			return false;
		}
		
		@Override
		public int hashCode() {
			return (chunkX*1281773681) | (chunkZ*1298815619) | (chunkDimension*2022620329); 
		}
	}
	
	class ChunkLogEvent {
		Event event;
		int stackTraceIndex;
		
		ChunkLogEvent(Event e, int trace) {
			event = e;
			stackTraceIndex = trace;
		}
	}
	
	class ChunkLog {
		ChunkLogCoords coords;
		ChunkLogEvent event;
		
		ChunkLog(ChunkLogCoords c, ChunkLogEvent e){
			coords = c;
			event = e;
		}
		ChunkLog(int x, int z, int d, Event event, int stacktraceId) {
			this.coords = new ChunkLogCoords(x,z,d);
			this.event = new ChunkLogEvent(event,stacktraceId);
		}
	}
	
	ArrayList<ChunkLog> eventsThisGametick;
	
	public CarpetClientChunkLogger(){
		if(stackTraces == null) {
			stackTraces = new StackTraces();
		}
		if(clients == null) {
			this.clients = new ChunkLoggerSerializer();
		}
		String nullTrace = "";
		
		this.eventsThisGametick = new ArrayList();
	}
	
	public ArrayList<ChunkLog> getInitialChunksForNewClient(MinecraftServer server){
		ArrayList<ChunkLog> forNewClient = new ArrayList();
		int dimension = -1;
		for(World w: server.worlds) {
			ChunkProviderServer provider = (ChunkProviderServer)(w.getChunkProvider());
			for(Chunk c : provider.getLoadedChunks()) {
				forNewClient.add(new ChunkLog(c.x,c.z,dimension,Event.LOADING,0));
				if(provider.isChunkUnloadScheduled(c.x, c.z)) {
					forNewClient.add(new ChunkLog(c.x,c.z,dimension,Event.QUEUE_UNLOAD,0));
					if(!c.unloadQueued) {
						forNewClient.add(new ChunkLog(c.x,c.z,dimension,Event.CANCEL_UNLOAD,0));
					}
				}
			}
			dimension++;
			PlayerChunkMap chunkmap = ((WorldServer)w).getPlayerChunkMap();
			Iterator<Chunk> i = chunkmap.getChunkIterator();
			while(i.hasNext()) {
				Chunk c = i.next();
				forNewClient.add(new ChunkLog(c.x,c.z,dimension,Event.PLAYER_ENTERS,0));
			}
		}
		return forNewClient;
	}
	
	public ArrayList<ChunkLog> getEventsThisGametick() {
		return this.eventsThisGametick;
	}
	
	void log(int x, int z, int d, Event event, int stackTrace) {
		ChunkLogCoords c = new ChunkLogCoords(x,z,d);
		ChunkLogEvent e = new ChunkLogEvent(event, stackTrace);
		this.eventsThisGametick.add(new ChunkLog(c,e));
	}
	
	int getWorldIndex(World w) {
		int i = 0;
		for(World o:w.getMinecraftServer().worlds) {
			if(o==w) {
				return i;
			}
			i++;
		}
		return -1;
	}
	
	public void log(World w, int x, int z, Event e) {
		if(!enabled || !clients.hasListeners() ) {
			return;
		}
		int stacktraceid = 0;
		if(allowStacktraces && clients.hasStackTraceListeners()) {
			stacktraceid = stackTraces.internStackTrace();
		}
		log(x, z, getWorldIndex(w), e, stacktraceid);
	}

	public void startTick() {
		this.eventsThisGametick.clear();
		stackTraces.startTick();
	}
	
	public void sendAll() {
		clients.sendUpdates();
	}

	public class StackTraces {
		// TODO: make version configurable 
		StackTraceDeobfuscator deobf = StackTraceDeobfuscator.create().withMinecraftVersion("1.12").withSnapshotMcpNames("20180713-1.12");		
		HashMap<String,Integer> stackTraceToIndex = new HashMap();
		ArrayList<String> allTracesDeobfuscated = new ArrayList();
		ArrayList<String> newTracesDeobfuscated = new ArrayList();

		public StackTraces() {
			clear();
		}
		
		public void clear() {
			stackTraceToIndex.clear();
			allTracesDeobfuscated.clear();
			newTracesDeobfuscated.clear();
			this.internString("");
		}
		
		public void startTick() {
			this.newTracesDeobfuscated.clear();
		}

		public String getString(int i) {
			return this.allTracesDeobfuscated.get(i);
		}
		
		private ArrayList<String> getInitialStackTracesForNewClient(){
			return this.allTracesDeobfuscated;
		}
		
		private ArrayList<String> getNewStackTraces() {
			return this.newTracesDeobfuscated;
		}
		
		private int getStackTracesCount() {
			return this.allTracesDeobfuscated.size();
		}
		
		private int internString(String obfuscated, String deobfuscated) {
			Integer i = stackTraceToIndex.get(obfuscated);
			if(i == null) {
				i = this.allTracesDeobfuscated.size();
				this.allTracesDeobfuscated.add(deobfuscated);
				this.newTracesDeobfuscated.add(deobfuscated);
				stackTraceToIndex.put(obfuscated, i);
			}
			return i;
		}
		
		private int internString(String s) {
			return this.internString(s,s);
		}
		
		private int internStackTrace() {
			StackTraceElement[] trace = new Throwable().getStackTrace();
			String obfuscated = asString(trace,false);
			Integer i = this.stackTraceToIndex.get(obfuscated);
			if(i != null) {
				return i;
			}
			else {
				String deobfuscated = asString(trace, true);
				return this.internString(obfuscated, deobfuscated);
			}
		}
		
		private String asString(StackTraceElement[] trace, boolean deobfuscated) {
			if(deobfuscated) {
				trace =  deobf.withStackTrace(trace).deobfuscate();
			}
			String stacktrace = new String();
			for(StackTraceElement e:trace) {
				if("CarpetClientChunkLogger.java".equals(e.getFileName())) {
					continue;
				}
				if(!stacktrace.isEmpty()) {
					stacktrace += System.lineSeparator();
				}
				stacktrace += e.toString();
			}
			return stacktrace;
		}
	}
	
	class ChunkLoggerSerializer {
		
		public static final int PACKET_EVENTS = 0;
		public static final int PACKET_STACKTRACE = 1;

		private HashSet<EntityPlayerMP> playersLoggingChunks = new HashSet();
		private HashSet<EntityPlayerMP> playersGettingStackTraces = new HashSet();
		
		public void registerPlayer(EntityPlayerMP sender, PacketBuffer data) {
			boolean addPlayer = data.readBoolean();
			boolean getStackTraces = data.readBoolean();
			if (addPlayer) {
				if(getStackTraces) {
					playersGettingStackTraces.add(sender);
					this.sendInitalStackTraces(sender);
				}
				playersLoggingChunks.add(sender);
				this.sendInitalChunks(sender);
			} else {
				this.unregisterPlayer(sender);
			}
		}

		public void unregisterPlayer(EntityPlayerMP player) {
			playersLoggingChunks.remove(player);
			playersGettingStackTraces.remove(player);
			if(!hasStackTraceListeners()) {
				stackTraces.clear();
			}
		}
		
		private void sendInitalChunks(EntityPlayerMP sender) {
			MinecraftServer server = sender.getServer();
			NBTTagCompound data = serializeEvents(sender.getServer(), getInitialChunksForNewClient(server),server.getTickCounter()-1);
			CarpetClientMessageHandler.sendNBTChunkData(sender, PACKET_EVENTS, data);
		}
		
		private void sendInitalStackTraces(EntityPlayerMP sender) {
			NBTTagCompound stackData = serializeStackTraces(stackTraces.getInitialStackTracesForNewClient(),0);
			if(stackData != null) {
				CarpetClientMessageHandler.sendNBTChunkData(sender, PACKET_STACKTRACE, stackData);
			}
		}

		public boolean hasListeners() {
			return !this.playersLoggingChunks.isEmpty();
		}
		
		private boolean hasStackTraceListeners() {
			return !this.playersGettingStackTraces.isEmpty();
		}
		
		private void sendUpdates() {
			if(this.playersLoggingChunks.isEmpty()) {
				return;
			}
			MinecraftServer server = this.playersLoggingChunks.iterator().next().server;
			NBTTagCompound chunkData = serializeEvents(server, getEventsThisGametick(), server.getTickCounter());
			if(chunkData != null) {
				for(EntityPlayerMP player: this.playersLoggingChunks) {
					CarpetClientMessageHandler.sendNBTChunkData(player, PACKET_EVENTS, chunkData);
				}
			}
			
			if(this.playersGettingStackTraces.isEmpty()) {
				return;
			}
			ArrayList<String> traces = stackTraces.getNewStackTraces();
			int tracesStartId = stackTraces.getStackTracesCount() - traces.size();
			NBTTagCompound stackData = serializeStackTraces(traces, tracesStartId);
			if(stackData != null) {
				for(EntityPlayerMP player: this.playersGettingStackTraces) {
					CarpetClientMessageHandler.sendNBTChunkData(player, PACKET_STACKTRACE, stackData);
				}
			}
		}
		
		private NBTTagCompound serializeEvents(MinecraftServer server, ArrayList<ChunkLog> events, int gametick) {
			if(events.isEmpty()) {
				return null;
			}
			NBTTagCompound chunkData = new NBTTagCompound();
			NBTTagList list = new NBTTagList();
			for (ChunkLog log : events) {
				NBTTagCompound data = new NBTTagCompound();
				data.setInteger("x", log.coords.chunkX);
				data.setInteger("z", log.coords.chunkZ);
				data.setInteger("d", log.coords.chunkDimension);
				data.setInteger("event", log.event.event.ordinal());
				data.setInteger("trace", log.event.stackTraceIndex);
				list.appendTag(data);
			}
			chunkData.setTag("data", list);
			chunkData.setInteger("time", gametick);
			return chunkData;
		}

		private NBTTagCompound serializeStackTraces(ArrayList<String> traces, int startId) {
			if(traces.isEmpty()) {
				return null;
			}
			NBTTagList list = new NBTTagList();
			int i = 0;
			for(String s: traces) {
				NBTTagCompound stackTrace = new NBTTagCompound();
				stackTrace.setInteger("id", startId+i);
				stackTrace.setString("stack", s);
				list.appendTag(stackTrace);
				++i;
			}
			NBTTagCompound stackList = new NBTTagCompound();
			stackList.setTag("stackList", list);
			return stackList;
		}
	}


}
