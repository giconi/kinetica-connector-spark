package com.gpudb.spark.output;

import com.gpudb.BulkInserter;
import com.gpudb.GPUdb;
import com.gpudb.GenericRecord;
import com.gpudb.Type;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * GPUdb data processor, used in accepting object records or parseable string
 * records of a given type and inserting them into the database
 */
public class GPUdbWriter implements Serializable
{
	private static final long serialVersionUID = -5273795273398765842L;

	private static final Logger log = LoggerFactory.getLogger(GPUdbWriter.class);

	/** SparkConf property name for GPUdb connection host */
	public static final String PROP_GPUDB_HOST = "gpudb.host";
	/** SparkConf property name for GPUdb connection port */
	public static final String PROP_GPUDB_PORT = "gpudb.port";
	/** SparkConf property name for GPUdb threads */
	public static final String PROP_GPUDB_THREADS = "gpudb.threads";
	/** SparkConf property name for GPUdb table name */
	public static final String PROP_GPUDB_TABLE_NAME = "gpudb.table";
	/** SparkConf property name for GPUdb insert size */
	public static final String PROP_GPUDB_INSERT_SIZE = "gpudb.insert.size";

	private static final int DEFAULT_PORT = 9191;
	private static final int DEFAULT_THREADS = 4;
	private static final int DEFAULT_INSERT_SIZE = 1;

	private String host;
	private int port;
	private int threads;
	private String tableName;
	private int insertSize;


	/**
	 * Creates a GPUdbWriter with parameters specified through a
	 * SparkConf.  The required parameters are:
	 * <ul>
	 * <li>gpudb.host - hostname/IP of GPUdb server</li>
	 * <li>gpudb.port - port on which GPUdb service is listening</li>
	 * <li>gpudb.threads - number of threads to use in the writing</li>
	 * <li>gpudb.table - name of table to which records will be written</li>
	 * <li>gpudb.insert.size - number of records to write per write</li>
	 * </ul>
	 * 
	 * @param conf Spark configuration containing the GPUdb setup parameters for
	 *        this reader
	 */
	public GPUdbWriter(SparkConf conf)
	{
		host = conf.get(PROP_GPUDB_HOST);
		port = conf.getInt(PROP_GPUDB_PORT, DEFAULT_PORT);
		threads = conf.getInt(PROP_GPUDB_THREADS, DEFAULT_THREADS);
		tableName = conf.get(PROP_GPUDB_TABLE_NAME);
		insertSize = conf.getInt(PROP_GPUDB_INSERT_SIZE, DEFAULT_INSERT_SIZE);

		if (host == null || host.isEmpty())
			throw new IllegalArgumentException("No GPUdb hostname defined");
		
		if (tableName == null || tableName.isEmpty())
			throw new IllegalArgumentException("No GPUdb table name defined");
	}

	/**
	 * Writes the contents of an RDD to GPUdb
	 * 
	 * @param rdd RDD to write to GPUdb
	 */
	public void write(JavaRDD<Map<String,Object>> rdd)
	{
		rdd.foreachPartition
		(
			new VoidFunction<Iterator<Map<String,Object>>>()
			{
				private static final long serialVersionUID = 1519062387719363984L;
				private List<Map<String,Object>> records = new ArrayList<>();
				
				@Override
				public void call(Iterator<Map<String,Object>> recordSet)
				{
					while (recordSet.hasNext())
					{
						Map<String,Object> record = recordSet.next();
						if (record != null)
							write(records, record);
					}
					flush(records);
				}
			}
		);
	}

	/**
	 * Writes the contents of a Spark stream to GPUdb
	 * 
	 * @param dstream data stream to write to GPUdb
	 */
	public void write(JavaDStream<Map<String,Object>> dstream)
	{
		final List<Map<String,Object>> records = new ArrayList<>();

		dstream.foreachRDD
		(
			new Function<JavaRDD<Map<String,Object>>, Void>()
			{
				private static final long serialVersionUID = -6215198148637505774L;

				@Override
				public Void call(JavaRDD<Map<String,Object>> rdd) throws Exception
				{
					rdd.foreachPartition
					(
						new VoidFunction<Iterator<Map<String,Object>>>()
						{
							private static final long serialVersionUID = -4370039011790218715L;

							@Override
							public void call(Iterator<Map<String,Object>> recordSet)
							{
								while (recordSet.hasNext())
								{
									Map<String,Object> record = recordSet.next();
									if (record != null)
										write(records, record);
								}
							}
						}
					);
					return null;
				}
			}
		);
	}

	/**
	 * Queues a record for writing to GPUdb
	 * 
	 * @param records queued list of records to which record will be added
	 * @param record record to write to GPUdb
	 */
	public void write(List<Map<String,Object>> records, Map<String,Object> record)
	{
		records.add(record);
		log.debug("Added <{}> to write queue", record);

		if (records.size() >= insertSize)
			flush(records);
	}

	/**
	 * Flushes the set of accumulated records, writing them to GPUdb
	 * 
	 * @param records queue of records to write to GPUdb
	 */
	public void flush(List<Map<String,Object>> records)
	{
		try
		{
			log.debug("Creating new GPUdb...");
			GPUdb gpudb = new GPUdb("http://" + host + ":" + port, new GPUdb.Options().setThreadCount(threads));
			
			Type type = Type.fromTable(gpudb, tableName);
	
			log.info("Writing <{}> records to table <{}>", records.size(), tableName);
			List<GenericRecord> genericRecords = new ArrayList<>();
			for (Map<String,Object> record : records)
			{
				GenericRecord genericRecord = new GenericRecord(type);
				
				for (Type.Column column : type.getColumns())
					genericRecord.put(column.getName(), record.get(column.getName()));

				genericRecords.add(genericRecord);
				log.debug("    Array Item: <{}>", record);
			}

			BulkInserter<GenericRecord> bi = new BulkInserter<>(gpudb, tableName, type, insertSize, GPUdb.options());
			bi.insert(genericRecords);
			bi.flush();
			
			records.clear();
		}
		catch (Exception ex)
		{
			log.error("Problem writing record(s)", ex);
		}
	}
}
