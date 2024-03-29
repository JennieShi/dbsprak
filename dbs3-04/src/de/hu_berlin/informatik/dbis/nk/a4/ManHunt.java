/**
 * Do not change package declaration or the main method!
 */
package de.hu_berlin.informatik.dbis.nk.a4;

import java.io.IOException;
import java.util.*;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SortedMapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import de.hu_berlin.informatik.dbis.nk.a4.util.Decoder;
import de.hu_berlin.informatik.dbis.nk.a4.util.Event;
import de.hu_berlin.informatik.dbis.nk.a4.util.EventText;

/**
 * @author Philipp Strobel
 * Hadoop-Info
 * ============
 * 	- Start the hadoop daemons: {HADOOP_HOME}/bin/start-all.sh ===> hadoop-stop
 * 	- Stop the daemons with: {HADOOP_HOME}/bin/stop-all.sh ===> hadoop-start
 *  - # NameNode - http://localhost:50070/ # JobTracker - http://localhost:50030/ 
 *  - {HADOOP_HOME}/bin/hadoop fs -put srcfile destfile
 *  - {HADOOP_HOME}/bin/hadoop fs -get src localdest
 *  - {HADOOP_HOME}/bin/hadoop fs -cat file(s)
 *  - {HADOOP_HOME}/bin/hadoop ===> hadoop
 */

public class ManHunt extends Configured implements Tool {
	private static int matrixsize = 8;
	private static boolean DEBUG = false;
	@Override
	public int run(String[] args) throws Exception {

		/*
		 * implement your job submission here!
		 */

		try {
			// funktioniert nicht wie gedacht! :(
			if(args.length > 2)matrixsize = Integer.parseInt(args[2]);
			if(args.length > 3){
				if(args[3].compareTo("-d") == 0)
					DEBUG = true;
			}
		} catch (Exception e) {
			System.err.println("Usage: ManHunt input output matrixsize");
			System.exit(1);
		}

		Job job = new Job();
		job.setJarByClass(ManHunt.class);
		job.setJobName("ManHunt");

		job.setMapperClass(Map.class);
		job.setReducerClass(Reduce.class);

		// 8 ReduceTask - fuer jede Bewegungsrichtung einen
		job.setNumReduceTasks(8);

		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(SortedMapWritable.class);

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(Text.class);

		job.setInputFormatClass(MatrixInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		TextInputFormat.setInputPaths(job, new Path(args[0]));
		TextOutputFormat.setOutputPath(job, new Path(args[1]));

		boolean success = job.waitForCompletion(true);

		return success ? 0 : 1;
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int ret;
		try {
			ret = ToolRunner.run(new ManHunt(), args);
			System.exit(ret);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	public static class Map extends Mapper<LongWritable, Text, IntWritable, SortedMapWritable> {
	
		private IntWritable o = new IntWritable(0);
	
		@SuppressWarnings("unchecked")
		@Override
		protected void map(LongWritable key, Text value,
				org.apache.hadoop.mapreduce.Mapper.Context context)
				throws IOException, InterruptedException {
	
			// eingelesene Matrix wird per Tokenizer in ihre einzelnen Zellen zerlegt
			StringTokenizer tokens = new StringTokenizer(value.toString());
			// Matrix wird serialisiert abgelegt - nur für Debug/Matrix-Ausgabe benoetigt
			int[] matrix = new int[(matrixsize*matrixsize)];
			int i = 0;
	
			List<HashMap<Number,Boolean>> events = new ArrayList<HashMap<Number,Boolean>>();
			events.add(new HashMap<Number, Boolean>()); events.add(new HashMap<Number, Boolean>());
			events.add(new HashMap<Number, Boolean>()); events.add(new HashMap<Number, Boolean>());
	
			// Erscheinen und Verschwinden des jeweiligen Personentyps (Polizei, Kriminelle) wird in jeweils seperaten Liste gespeichert
			while (tokens.hasMoreTokens()) {
				// Matrix-Feld analysieren
				int event = Decoder.decodeEvent(Integer.parseInt((tokens.nextToken()).toString()));
				int type = Decoder.decodeType(Integer.parseInt((tokens.nextToken()).toString()));
				// Eintraege schreiben...
				matrix[i++] = type * (event==2?-1:1);
				switch (matrix[i-1]) {
				case 2: // appearing cop
					events.get(0).put(i-1, true); break;
				case -2: // disappearing cop
					events.get(1).put(i-1, true); break;
				case 3: // appearing criminal
					events.get(2).put(i-1, true); break;
				case -3: // disappearing criminal
					events.get(3).put(i-1, true); break;
				}
			}
			// Bewegungen (Erscheinen/Verschwinden) miteinander verknuepfen -> der Polizisten
			SortedMapWritable[] v = new SortedMapWritable[8];
			int suchRichtungen[] = {-(matrixsize+1),-matrixsize,-(matrixsize-1),1,-1,matrixsize-1, matrixsize, matrixsize+1};
			// fuer jedes Verschwinden wird das korrespondierende Erscheinen gesucht 
			for ( Number elem : events.get(1).keySet() ){
				int[] richtungen = {elem.intValue() - matrixsize-1,elem.intValue()-matrixsize,elem.intValue()-matrixsize+1,elem.intValue()+1,elem.intValue()-1,elem.intValue()+matrixsize-1,elem.intValue()+matrixsize,elem.intValue()+matrixsize+1};
				for(int j = 0; j < 8; ++j){
					if(v[j] == null) v[j] = new SortedMapWritable(); // Liste in der gespeichert werden soll muss vorher angelegt werden
					if(events.get(0).containsKey(richtungen[j])){
						// passende Bewegung gefunden; pruefen, ob diese Person/Bewegung auch fuer ein Event geeignet/brauchbar ist
						if(!testCandidate(j,elem.intValue())){
							if(DEBUG) System.out.println("Candidate can't cause event: "+": ("+(elem.intValue()%matrixsize)+","+(elem.intValue()/matrixsize)+") -> ("+(richtungen[j]%matrixsize)+","+(richtungen[j]/matrixsize)+"); Richtung="+j);
							continue; 
						}
						// Personenbewegung ist potentiell fuer ein Event geeignet
						if(DEBUG) System.out.println(((int) key.get())+": ("+(elem.intValue()%matrixsize)+","+(elem.intValue()/matrixsize)+") -> ("+(richtungen[j]%matrixsize)+","+(richtungen[j]/matrixsize)+"); "+suchRichtungen[j]+"/"+(j)+";"+"(Cop); ID="+elem.intValue());
						// Typ, From, To, Direction, Matrix
						IntWritable[] tmp = { new IntWritable(2), new IntWritable(elem.intValue()), new IntWritable(richtungen[j]), new IntWritable(suchRichtungen[j]), new IntWritable((int) key.get())};
						// speichern der Person in der entsprechenden Bewegungsliste und Schluessel ist die aktuelle (Matrix-)Position
						v[j].put(new IntWritable(richtungen[j]),new IntArrayWritable(tmp));
						break;
					}
				}
			}
			// analog fuer die Kriminellen; Kriminelle werden in der entgegengesetzten Bewegungliste gespeichert >> nachher leichter bei Eventerkennung
			for ( Number elem : events.get(3).keySet() ){
				// 0    1   2   3  4    5   6  7
				// ^\, ^|, /^, ->, <-, ./, .|, \.
				int[] richtungen = {elem.intValue() - matrixsize-1,elem.intValue()-matrixsize,elem.intValue()-matrixsize+1,elem.intValue()+1,elem.intValue()-1,elem.intValue()+matrixsize-1,elem.intValue()+matrixsize,elem.intValue()+matrixsize+1};
				for(int j = 0; j < 8; ++j){
					if(v[7-j] == null) v[7-j] = new SortedMapWritable();
					if(events.get(2).containsKey(richtungen[j])){
						if(!testCandidate(j,elem.intValue())){
							if(DEBUG) System.out.println("Candidate can't cause event: "+": ("+(elem.intValue()%matrixsize)+","+(elem.intValue()/matrixsize)+") -> ("+(richtungen[j]%matrixsize)+","+(richtungen[j]/matrixsize)+"); Richtung="+j);
							continue; 
						}
						if(DEBUG) System.out.println(((int) key.get())+": ("+(elem.intValue()%matrixsize)+","+(elem.intValue()/matrixsize)+") -> ("+(richtungen[j]%matrixsize)+","+(richtungen[j]/matrixsize)+"); "+suchRichtungen[j]+"/"+(j)+";"+"(Criminal) ID="+((-1)*elem.intValue()));
						// Kriminelle werden in die entgegengesetzte Bewegungsrichtung "einsortiert"
						// Typ, From, To, Direction, Matrix
						IntWritable[] tmp = { new IntWritable(3),  new IntWritable(elem.intValue()),  new IntWritable(richtungen[j]), new IntWritable(suchRichtungen[j]), new IntWritable((int) key.get())};
						v[7-j].put(new IntWritable((-1)*richtungen[j]),new IntArrayWritable(tmp));
						break;
					}
				}
			}
			// Debug-Ausgaben ...
			if(DEBUG) System.out.println("\n---=== | ===---\n");
			
			if (DEBUG) {
				System.err.println(key.get());
				System.err.print("-----\n   0 1 2 3 4 5 6 7 \n====================");
				for (int i1 = 0; i1 < (matrixsize * matrixsize); ++i1) {
					if (i1 % matrixsize == 0)
						System.err.print("\n" + (i1 / matrixsize) + "|");
					System.err.print(matrix[i1] >= 0 ? " " + matrix[i1] : matrix[i1]);
				}
				System.err.println("\n====================\n^\\, ^|, /^, ->, <-, ./, .|, \\.");
			}
			for(int i1 = 0; i1 < 8; ++i1){
				if(v[i1] == null){
					if(DEBUG) System.err.print("0 , ");
					continue;
				}
				if(DEBUG) System.err.print(v[i1].size()+" , ");
				o.set(i1);
				// Bewegungsrichtungen mit nur einer Person muessen nicht untersucht werden! alle anderen Werden der Reduce-Fkt. uebergeben
				if(v[i1].size() > 1)
					context.write(new IntWritable(i1), v[i1]);
			}
			if(DEBUG) System.err.println("\n||||||||||||||||\n");
		}
		// prueft, ob eine Person sich in einem Bereich befindet, in dem auch ein Event auftreten kann
		private boolean testCandidate(int j, int value){
			int x = (value%matrixsize), y = (value/matrixsize);
			switch (j) {
			case 0: return x > 2 && y > 2;
			case 1: return y > 2;
			case 2: return x < (matrixsize-3) && y > 2;
			case 3: return x < (matrixsize-3);
			case 4: return x > 2;
			case 5: return x > 2 && y < (matrixsize-3);
			case 6: return y < (matrixsize-3);
			case 7: return x < (matrixsize-3) && y < (matrixsize-3);
			}
			return false;
		}
	}

	// Def. Uebergabeobjekt ...
	public static class IntArrayWritable extends ArrayWritable {
	    public IntArrayWritable() {
	        super(IntWritable.class);
	    }
	    public IntArrayWritable(IntWritable[] values) {
	        super(IntWritable.class, values);
	    }
	}

	public static class Reduce extends Reducer<IntWritable, SortedMapWritable, IntWritable, Text> {
	
		@SuppressWarnings("unchecked")
		@Override
		protected void reduce(IntWritable key, Iterable<SortedMapWritable> values,
				Context context)
				throws IOException, InterruptedException {
			int matrixsize = ManHunt.matrixsize;
			int count = 0;
			// jede Bewegungsrichtung wird untersucht
			for (SortedMapWritable v : values) {
				// Debugausgaben...
				if(DEBUG) {
					// Prints all detected Persons from specific direction
					System.out.println("---- ==== |"+key.get()+"/"+(++count)+"| ==== ----");
					for ( WritableComparable<IntWritable> elem : v.keySet() ){
						IntArrayWritable dataArray = (IntArrayWritable) v.get(elem);
						Writable[] data = dataArray.get();
						// type-0, start-1, end-2, richtung-3, schluessel-4
						System.out.println("-"+((IntWritable) data[4]).get() + ": ("
							+ (((IntWritable) data[1]).get() % matrixsize) + ","
							+ (((IntWritable) data[1]).get() / matrixsize) + ") -> ("
							+ (((IntWritable) data[2]).get() % matrixsize) + ","
							+ (((IntWritable) data[2]).get() / matrixsize) + "); "
							+ ((IntWritable) data[3]).get() + "/" + ((IntWritable) data[4]).get() + ";"
							+ "("+(((IntWritable) data[0]).get()==2?"Cop":"Criminal")+"); ID=" + ((IntWritable)elem).get());
					}
				}
				
				if(DEBUG) System.err.println("\n---- ==== |"+key.get()+"/"+(count)+"| ==== ----");
	
				// Bewegungsliste wird in Polizei und Kriminelle geteilt
				SortedMap<WritableComparable, Writable> Criminals = v.headMap(new IntWritable(0));
				SortedMap<WritableComparable, Writable> Cops = v.tailMap(new IntWritable(0));
				// in dieser Bewegungsrichtung gibt es entweder keine Cops oder Criminals ==> es kann kein Event auftreten
				if(Criminals.size() == 0 || Cops.size() == 0){
					if(DEBUG) System.err.println("Missing Type >> No Event! ~ #Crime = "+Criminals.size()+", #Cops = "+Cops.size());
					continue;
				}
				
				// Event moeglich...
				for ( WritableComparable<IntWritable> elem : Criminals.keySet() ){
					// fuer jeden Kriminellen wird die Bewegungsrichtung nach einem Polizisten abgesucht ...
					IntArrayWritable dataArray = (IntArrayWritable) Criminals.get(elem);
					Writable[] data = dataArray.get();
					// Infos abspeichern 
					// data = {Typ, Ankunftsfeld, Startfeld, Suchrichtung, Matrix}
					int crimeType = ((IntWritable) data[0]).get();
					int crimeToLoc = ((IntWritable) data[2]).get();
					int crimeFromLoc = ((IntWritable) data[1]).get();
					int crimeDirection = ((IntWritable) data[3]).get();
					int crimeMatrix = ((IntWritable) data[4]).get();
					//System.err.println(key.get()+"/"+crimeMatrix+": ("+(crimeFromLoc%matrixsize)+","+(crimeFromLoc/matrixsize)+") -> ("+(crimeToLoc%matrixsize)+", "+(crimeToLoc/matrixsize)+"); "+((crimeType==3)?"Criminal":"Cop"));
					int old = 0;
					// Bewegungsrichtung ablaufen ...
					for(int i=1,j=0; j >= 0 && j < matrixsize*matrixsize;++i){
						// nicht ueber die (Matrix-)Raender hinauslaufen ;) 
						old = j;
						j = crimeDirection*i+crimeToLoc;
						if((old % matrixsize == 0 && (j+1)%matrixsize == 0) ||(j % matrixsize == 0 && (old+1)%matrixsize == 0))break;
						// gibt es einen Polizisten in der Bewegungsrichtung (mit entgegengesetzter Bewegung)?
						if(Cops.containsKey(new IntWritable(j))){
							IntArrayWritable copDataArray = (IntArrayWritable) Cops.get(new IntWritable(j));
							Writable[] copData = copDataArray.get();
							// Beide untersuchten Personen muessen in der gleichen Matrix sein
							if(crimeMatrix != ((IntWritable) copData[4]).get()) continue;
							if(DEBUG) System.err.println(key.get()+"/"+crimeMatrix+": ("+(crimeFromLoc%matrixsize)+","+(crimeFromLoc/matrixsize)+") -> ("+(crimeToLoc%matrixsize)+", "+(crimeToLoc/matrixsize)+"); "+((crimeType==3)?"Criminal":"Cop")+"["+crimeDirection+"]");
							if(DEBUG) System.err.println(key.get()+"/"+((IntWritable) copData[4]).get()+": ("+(((IntWritable) copData[1]).get()%matrixsize)+","+(((IntWritable) copData[1]).get()/matrixsize)+") -> ("+(((IntWritable) copData[2]).get()%matrixsize)+", "+(((IntWritable) copData[2]).get()/matrixsize)+"); "+((((IntWritable) copData[0]).get()==3)?"Criminal":"Cop")+"["+((IntWritable) copData[3]).get()+"]");
							// Event erstellen und ausgeben; Schluessel der ausgabe = MatrixNr.
							Event detected = new Event();
							detected.setCriminalFromColumn(crimeFromLoc/matrixsize);
							detected.setCriminalFromLine(crimeFromLoc%matrixsize);
							detected.setCriminalToColumn(crimeToLoc/matrixsize);
							detected.setCriminalToLine(crimeToLoc%matrixsize);
							detected.setPolicemanFromColumn(((IntWritable) copData[1]).get()/matrixsize);
							detected.setPolicemanFromLine(((IntWritable) copData[1]).get()%matrixsize);
							detected.setPolicemanToColumn(((IntWritable) copData[2]).get()/matrixsize);
							detected.setPolicemanToLine(((IntWritable) copData[2]).get()%matrixsize);
							context.write((IntWritable) copData[4], EventText.getEventTextValue(detected));
						}
	
					}
				}
				// bearbeitete  koennen geloescht werden; musste sein, weil sonst doppelungen auftraten!?
				v.clear();
				Criminals.clear();
				Cops.clear();
			}
		}
	}

	// Klassen zum einlesen
	public static class MatrixReader extends RecordReader<LongWritable, Text> {
	    private long end;
	    private boolean stillInChunk = true;
	
	    private LongWritable key = new LongWritable();
	    private Text value = new Text();
	    private int matrixCount = 0;
	
	    private FSDataInputStream fsin;
	    private DataOutputBuffer buffer = new DataOutputBuffer();
	
	    private byte[] endTag = "\n\n".getBytes();
	
	    public void initialize(InputSplit inputSplit, TaskAttemptContext taskAttemptContext) throws IOException, InterruptedException {
	        FileSplit split = (FileSplit) inputSplit;
	        Configuration conf = taskAttemptContext.getConfiguration();
	        Path path = split.getPath();
	        FileSystem fs = path.getFileSystem(conf);
	
	        fsin = fs.open(path);
	
	        long start = split.getStart();
	        end = split.getStart() + split.getLength();
	        fsin.seek(start);
	
	        if (start != 0) {
	            readUntilMatch(endTag, false);
	        }
	    }
	
	    public boolean nextKeyValue() throws IOException {
	        if (!stillInChunk) return false;
	
	        boolean status = readUntilMatch(endTag, true);
	       
	        value = new Text();
	        value.set(buffer.getData(), 0, buffer.getLength());
	
	        //key = new IntWritable((int) fsin.getPos());
	        key = new LongWritable(matrixCount++);
	        buffer.reset();
	
	        if (!status) {
	            stillInChunk = false;
	        }
	
	        return true;
	    }
	
	    public LongWritable getCurrentKey() throws IOException, InterruptedException {
	        return key;
	    }
	
	    public Text getCurrentValue() throws IOException, InterruptedException {
	        return value;
	    }
	
	    public float getProgress() throws IOException, InterruptedException {
	        return 0;
	    }
	
	    public void close() throws IOException {
	        fsin.close();
	    }
	
	    private boolean readUntilMatch(byte[] match, boolean withinBlock) throws IOException {
	        int i = 0;
	        while (true) {
	            int b = fsin.read();
	            if (b == -1) return false;
	            if (withinBlock) buffer.write(b);
	            if (b == match[i]) {
	                i++;
	                if (i >= match.length) {
	                    return fsin.getPos() < end;
	                }
	            } else i = 0;
	        }
	    }
	}

	// lese gleich gesamten Matrix-Block ...
	public static class MatrixInputFormat extends TextInputFormat {
	    @Override
	    public RecordReader<LongWritable, Text> createRecordReader(InputSplit inputSplit, TaskAttemptContext taskAttemptContext) {
	        return new MatrixReader();
	    }
	}
}