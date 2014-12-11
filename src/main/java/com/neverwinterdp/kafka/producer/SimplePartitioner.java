package com.neverwinterdp.kafka.producer;

import kafka.producer.Partitioner;
import kafka.utils.VerifiableProperties;

/**
 * A partitioner that writes to the specified partition.
 * 
 * The required partition is passed to the Partitioner as a string via 
 * 
 * <code> KeyedMessage<String, String> data = new KeyedMessage<String, String>(topic,partition,message);</code>
 * */
public class SimplePartitioner implements Partitioner {

  public SimplePartitioner(VerifiableProperties props) {
  }
  
  @Override
  public int partition(Object arg0, int arg1) {
    return Integer.parseInt((String) arg0);
  }
}