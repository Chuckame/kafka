/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.log

import kafka.utils.TestUtils
import kafka.utils.TestUtils.random
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.compress.Compression
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.record._
import org.apache.kafka.common.utils.{MockTime, Time, Utils}
import org.apache.kafka.coordinator.transaction.TransactionLogConfig
import org.apache.kafka.server.util.MockScheduler
import org.apache.kafka.storage.internals.checkpoint.LeaderEpochCheckpointFile
import org.apache.kafka.storage.internals.epoch.LeaderEpochFileCache
import org.apache.kafka.storage.internals.log._
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.{CsvSource, ValueSource}

import java.io.{File, RandomAccessFile}
import java.util.{Optional, OptionalLong}
import scala.collection._
import scala.jdk.CollectionConverters._

class LogSegmentTest {
  private val topicPartition = new TopicPartition("topic", 0)
  private val segments = mutable.ArrayBuffer[LogSegment]()
  private var logDir: File = _

  /* create a segment with the given base offset */
  def createSegment(offset: Long,
                    indexIntervalBytes: Int = 10,
                    time: Time = Time.SYSTEM): LogSegment = {
    val seg = LogTestUtils.createSegment(offset, logDir, indexIntervalBytes, time)
    segments += seg
    seg
  }

  /* create a ByteBufferMessageSet for the given messages starting from the given offset */
  def records(offset: Long, records: String*): MemoryRecords = {
    MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V1, offset, Compression.NONE, TimestampType.CREATE_TIME,
      records.map { s => new SimpleRecord(offset * 10, s.getBytes) }: _*)
  }

  @BeforeEach
  def setup(): Unit = {
    logDir = TestUtils.tempDir()
  }

  @AfterEach
  def teardown(): Unit = {
    segments.foreach(_.close())
    Utils.delete(logDir)
  }

  /**
   * LogSegmentOffsetOverflowException should be thrown while appending the logs if:
   * 1. largestOffset - baseOffset < 0
   * 2. largestOffset - baseOffset > Integer.MAX_VALUE
   */
  @ParameterizedTest
  @CsvSource(Array(
    "0, -2147483648",
    "0, 2147483648",
    "1, 0",
    "100, 10",
    "2147483648, 0",
    "-2147483648, 0",
    "2147483648,4294967296"
  ))
  def testAppendForLogSegmentOffsetOverflowException(baseOffset: Long, largestOffset: Long): Unit = {
    val seg = createSegment(baseOffset)
    val currentTime = Time.SYSTEM.milliseconds()
    val shallowOffsetOfMaxTimestamp = largestOffset
    val memoryRecords = records(0, "hello")
    assertThrows(classOf[LogSegmentOffsetOverflowException], () => {
      seg.append(largestOffset, currentTime, shallowOffsetOfMaxTimestamp, memoryRecords)
    })
  }

  /**
   * A read on an empty log segment should return null
   */
  @Test
  def testReadOnEmptySegment(): Unit = {
    val seg = createSegment(40)
    val read = seg.read(40, 300)
    assertNull(read, "Read beyond the last offset in the segment should be null")
  }

  /**
   * Reading from before the first offset in the segment should return messages
   * beginning with the first message in the segment
   */
  @Test
  def testReadBeforeFirstOffset(): Unit = {
    val seg = createSegment(40)
    val ms = records(50, "hello", "there", "little", "bee")
    seg.append(53, RecordBatch.NO_TIMESTAMP, -1L, ms)
    val read = seg.read(41, 300).records
    checkEquals(ms.records.iterator, read.records.iterator)
  }

  /**
   * If we read from an offset beyond the last offset in the segment we should get null
   */
  @Test
  def testReadAfterLast(): Unit = {
    val seg = createSegment(40)
    val ms = records(50, "hello", "there")
    seg.append(51, RecordBatch.NO_TIMESTAMP, -1L, ms)
    val read = seg.read(52, 200)
    assertNull(read, "Read beyond the last offset in the segment should give null")
  }

  /**
   * If we read from an offset which doesn't exist we should get a message set beginning
   * with the least offset greater than the given startOffset.
   */
  @Test
  def testReadFromGap(): Unit = {
    val seg = createSegment(40)
    val ms = records(50, "hello", "there")
    seg.append(51, RecordBatch.NO_TIMESTAMP, -1L, ms)
    val ms2 = records(60, "alpha", "beta")
    seg.append(61, RecordBatch.NO_TIMESTAMP, -1L, ms2)
    val read = seg.read(55, 200)
    checkEquals(ms2.records.iterator, read.records.records.iterator)
  }

  @ParameterizedTest(name = "testReadWhenNoMaxPosition minOneMessage = {0}")
  @ValueSource(booleans = Array(true, false))
  def testReadWhenNoMaxPosition(minOneMessage: Boolean): Unit = {
    val maxPosition: Optional[java.lang.Long] = Optional.empty()
    val maxSize = 1
    val seg = createSegment(40)
    val ms = records(50, "hello", "there")
    seg.append(51, RecordBatch.NO_TIMESTAMP, -1L, ms)
    // read before first offset
    var read = seg.read(48, maxSize, maxPosition, minOneMessage)
    assertEquals(new LogOffsetMetadata(48, 40, 0), read.fetchOffsetMetadata)
    assertTrue(read.records.records().iterator().asScala.isEmpty)
    // read at first offset
    read = seg.read(50, maxSize, maxPosition, minOneMessage)
    assertEquals(new LogOffsetMetadata(50, 40, 0), read.fetchOffsetMetadata)
    assertTrue(read.records.records().iterator().asScala.isEmpty)
    // read at last offset
    read = seg.read(51, maxSize, maxPosition, minOneMessage)
    assertEquals(new LogOffsetMetadata(51, 40, 39), read.fetchOffsetMetadata)
    assertTrue(read.records.records().iterator().asScala.isEmpty)
    // read at log-end-offset
    read = seg.read(52, maxSize, maxPosition, minOneMessage)
    assertNull(read)
    // read beyond log-end-offset
    read = seg.read(53, maxSize, maxPosition, minOneMessage)
    assertNull(read)
  }

  /**
   * In a loop append two messages then truncate off the second of those messages and check that we can read
   * the first but not the second message.
   */
  @Test
  def testTruncate(): Unit = {
    val seg = createSegment(40)
    var offset = 40
    for (_ <- 0 until 30) {
      val ms1 = records(offset, "hello")
      seg.append(offset, RecordBatch.NO_TIMESTAMP, -1L, ms1)
      val ms2 = records(offset + 1, "hello")
      seg.append(offset + 1, RecordBatch.NO_TIMESTAMP, -1L, ms2)
      // check that we can read back both messages
      val read = seg.read(offset, 10000)
      assertEquals(List(ms1.records.iterator.next(), ms2.records.iterator.next()), read.records.records.asScala.toList)
      // now truncate off the last message
      seg.truncateTo(offset + 1)
      val read2 = seg.read(offset, 10000)
      assertEquals(1, read2.records.records.asScala.size)
      checkEquals(ms1.records.iterator, read2.records.records.iterator)
      offset += 1
    }
  }

  @Test
  def testTruncateEmptySegment(): Unit = {
    // This tests the scenario in which the follower truncates to an empty segment. In this
    // case we must ensure that the index is resized so that the log segment is not mistakenly
    // rolled due to a full index

    val maxSegmentMs = 300000
    val time = new MockTime
    val seg = createSegment(0, time = time)
    // Force load indexes before closing the segment
    seg.timeIndex
    seg.offsetIndex
    seg.close()

    val reopened = createSegment(0, time = time)
    assertEquals(0, seg.timeIndex.sizeInBytes)
    assertEquals(0, seg.offsetIndex.sizeInBytes)

    time.sleep(500)
    reopened.truncateTo(57)
    assertEquals(0, reopened.timeWaitedForRoll(time.milliseconds(), RecordBatch.NO_TIMESTAMP))
    assertFalse(reopened.timeIndex.isFull)
    assertFalse(reopened.offsetIndex.isFull)

    var rollParams = new RollParams(maxSegmentMs, Int.MaxValue, RecordBatch.NO_TIMESTAMP, 100L, 1024,
      time.milliseconds())
    assertFalse(reopened.shouldRoll(rollParams))

    // The segment should not be rolled even if maxSegmentMs has been exceeded
    time.sleep(maxSegmentMs + 1)
    assertEquals(maxSegmentMs + 1, reopened.timeWaitedForRoll(time.milliseconds(), RecordBatch.NO_TIMESTAMP))
    rollParams = new RollParams(maxSegmentMs, Int.MaxValue, RecordBatch.NO_TIMESTAMP, 100L, 1024, time.milliseconds())
    assertFalse(reopened.shouldRoll(rollParams))

    // But we should still roll the segment if we cannot fit the next offset
    rollParams = new RollParams(maxSegmentMs, Int.MaxValue, RecordBatch.NO_TIMESTAMP,
      Int.MaxValue.toLong + 200L, 1024, time.milliseconds())
    assertTrue(reopened.shouldRoll(rollParams))
  }

  @Test
  def testReloadLargestTimestampAndNextOffsetAfterTruncation(): Unit = {
    val numMessages = 30
    val seg = createSegment(40, 2 * records(0, "hello").sizeInBytes - 1)
    var offset = 40
    for (_ <- 0 until numMessages) {
      seg.append(offset, offset, offset, records(offset, "hello"))
      offset += 1
    }
    assertEquals(offset, seg.readNextOffset)

    val expectedNumEntries = numMessages / 2 - 1
    assertEquals(expectedNumEntries, seg.timeIndex.entries, s"Should have $expectedNumEntries time indexes")

    seg.truncateTo(41)
    assertEquals(0, seg.timeIndex.entries, s"Should have 0 time indexes")
    assertEquals(400L, seg.largestTimestamp, s"Largest timestamp should be 400")
    assertEquals(41, seg.readNextOffset)
  }

  /**
   * Test truncating the whole segment, and check that we can reappend with the original offset.
   */
  @Test
  def testTruncateFull(): Unit = {
    // test the case where we fully truncate the log
    val time = new MockTime
    val seg = createSegment(40, time = time)
    seg.append(41, RecordBatch.NO_TIMESTAMP, -1L, records(40, "hello", "there"))

    // If the segment is empty after truncation, the create time should be reset
    time.sleep(500)
    assertEquals(500, seg.timeWaitedForRoll(time.milliseconds(), RecordBatch.NO_TIMESTAMP))

    seg.truncateTo(0)
    assertEquals(0, seg.timeWaitedForRoll(time.milliseconds(), RecordBatch.NO_TIMESTAMP))
    assertFalse(seg.timeIndex.isFull)
    assertFalse(seg.offsetIndex.isFull)
    assertNull(seg.read(0, 1024), "Segment should be empty.")

    seg.append(41, RecordBatch.NO_TIMESTAMP, -1L, records(40, "hello", "there"))
  }

  /**
   * Append messages with timestamp and search message by timestamp.
   */
  @Test
  def testFindOffsetByTimestamp(): Unit = {
    val messageSize = records(0, s"msg00").sizeInBytes
    val seg = createSegment(40, messageSize * 2 - 1)
    // Produce some messages
    for (i <- 40 until 50)
      seg.append(i, i * 10, i, records(i, s"msg$i"))

    assertEquals(490, seg.largestTimestamp)
    // Search for an indexed timestamp
    assertEquals(42, seg.findOffsetByTimestamp(420, 0L).get.offset)
    assertEquals(43, seg.findOffsetByTimestamp(421, 0L).get.offset)
    // Search for an un-indexed timestamp
    assertEquals(43, seg.findOffsetByTimestamp(430, 0L).get.offset)
    assertEquals(44, seg.findOffsetByTimestamp(431, 0L).get.offset)
    // Search beyond the last timestamp
    assertEquals(Optional.empty(), seg.findOffsetByTimestamp(491, 0L))
    // Search before the first indexed timestamp
    assertEquals(41, seg.findOffsetByTimestamp(401, 0L).get.offset)
    // Search before the first timestamp
    assertEquals(40, seg.findOffsetByTimestamp(399, 0L).get.offset)
  }

  /**
   * Test that offsets are assigned sequentially and that the nextOffset variable is incremented
   */
  @Test
  def testNextOffsetCalculation(): Unit = {
    val seg = createSegment(40)
    assertEquals(40, seg.readNextOffset)
    seg.append(52, RecordBatch.NO_TIMESTAMP, -1L, records(50, "hello", "there", "you"))
    assertEquals(53, seg.readNextOffset)
  }

  /**
   * Test that we can change the file suffixes for the log and index files
   */
  @Test
  def testChangeFileSuffixes(): Unit = {
    val seg = createSegment(40)
    val logFile = seg.log.file
    val indexFile = seg.offsetIndexFile
    val timeIndexFile = seg.timeIndexFile
    // Ensure that files for offset and time indices have not been created eagerly.
    assertFalse(seg.offsetIndexFile.exists)
    assertFalse(seg.timeIndexFile.exists)
    seg.changeFileSuffixes("", ".deleted")
    // Ensure that attempt to change suffixes for non-existing offset and time indices does not create new files.
    assertFalse(seg.offsetIndexFile.exists)
    assertFalse(seg.timeIndexFile.exists)
    // Ensure that file names are updated accordingly.
    assertEquals(logFile.getAbsolutePath + ".deleted", seg.log.file.getAbsolutePath)
    assertEquals(indexFile.getAbsolutePath + ".deleted", seg.offsetIndexFile.getAbsolutePath)
    assertEquals(timeIndexFile.getAbsolutePath + ".deleted", seg.timeIndexFile.getAbsolutePath)
    assertTrue(seg.log.file.exists)
    // Ensure lazy creation of offset index file upon accessing it.
    seg.offsetIndex()
    assertTrue(seg.offsetIndexFile.exists)
    // Ensure lazy creation of time index file upon accessing it.
    seg.timeIndex()
    assertTrue(seg.timeIndexFile.exists)
  }

  /**
   * Create a segment with some data and an index. Then corrupt the index,
   * and recover the segment, the entries should all be readable.
   */
  @Test
  def testRecoveryFixesCorruptIndex(): Unit = {
    val seg = createSegment(0)
    for (i <- 0 until 100)
      seg.append(i, RecordBatch.NO_TIMESTAMP, -1L, records(i, i.toString))
    val indexFile = seg.offsetIndexFile
    writeNonsenseToFile(indexFile, 5, indexFile.length.toInt)
    seg.recover(newProducerStateManager(), Optional.empty())
    for (i <- 0 until 100) {
      val records = seg.read(i, 1, Optional.of(seg.size()), true).records.records
      assertEquals(i, records.iterator.next().offset)
    }
  }

  @Test
  def testRecoverTransactionIndex(): Unit = {
    val segment = createSegment(100)
    val producerEpoch = 0.toShort
    val partitionLeaderEpoch = 15
    val sequence = 100

    val pid1 = 5L
    val pid2 = 10L

    // append transactional records from pid1
    segment.append(101L, RecordBatch.NO_TIMESTAMP,
      100L, MemoryRecords.withTransactionalRecords(100L, Compression.NONE,
        pid1, producerEpoch, sequence, partitionLeaderEpoch, new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    // append transactional records from pid2
    segment.append(103L, RecordBatch.NO_TIMESTAMP, 102L, MemoryRecords.withTransactionalRecords(102L, Compression.NONE,
        pid2, producerEpoch, sequence, partitionLeaderEpoch, new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    // append non-transactional records
    segment.append(105L, RecordBatch.NO_TIMESTAMP, 104L, MemoryRecords.withRecords(104L, Compression.NONE,
        partitionLeaderEpoch, new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    // abort the transaction from pid2 (note LSO should be 100L since the txn from pid1 has not completed)
    segment.append(106L, RecordBatch.NO_TIMESTAMP, 106L,
      endTxnRecords(ControlRecordType.ABORT, pid2, producerEpoch, offset = 106L))

    // commit the transaction from pid1
    segment.append(107L, RecordBatch.NO_TIMESTAMP, 107L,
      endTxnRecords(ControlRecordType.COMMIT, pid1, producerEpoch, offset = 107L))

    var stateManager = newProducerStateManager()
    segment.recover(stateManager, Optional.empty())
    assertEquals(108L, stateManager.mapEndOffset)


    var abortedTxns = segment.txnIndex.allAbortedTxns
    assertEquals(1, abortedTxns.size)
    var abortedTxn = abortedTxns.get(0)
    assertEquals(pid2, abortedTxn.producerId)
    assertEquals(102L, abortedTxn.firstOffset)
    assertEquals(106L, abortedTxn.lastOffset)
    assertEquals(100L, abortedTxn.lastStableOffset)

    // recover again, but this time assuming the transaction from pid2 began on a previous segment
    stateManager = newProducerStateManager()
    stateManager.loadProducerEntry(new ProducerStateEntry(pid2, producerEpoch, 0, RecordBatch.NO_TIMESTAMP, OptionalLong.of(75L), java.util.Optional.of(new BatchMetadata(10, 10L, 5, RecordBatch.NO_TIMESTAMP))))
    segment.recover(stateManager, Optional.empty())
    assertEquals(108L, stateManager.mapEndOffset)

    abortedTxns = segment.txnIndex.allAbortedTxns
    assertEquals(1, abortedTxns.size)
    abortedTxn = abortedTxns.get(0)
    assertEquals(pid2, abortedTxn.producerId)
    assertEquals(75L, abortedTxn.firstOffset)
    assertEquals(106L, abortedTxn.lastOffset)
    assertEquals(100L, abortedTxn.lastStableOffset)
  }

  /**
   * Create a segment with some data, then recover the segment.
   * The epoch cache entries should reflect the segment.
   */
  @Test
  def testRecoveryRebuildsEpochCache(): Unit = {
    val seg = createSegment(0)

    val checkpoint: LeaderEpochCheckpointFile = new LeaderEpochCheckpointFile(TestUtils.tempFile(), new LogDirFailureChannel(1))

    val cache = new LeaderEpochFileCache(topicPartition, checkpoint, new MockScheduler(new MockTime()))
    seg.append(105L, RecordBatch.NO_TIMESTAMP, 104L, MemoryRecords.withRecords(104L, Compression.NONE, 0,
        new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    seg.append(107L, RecordBatch.NO_TIMESTAMP, 106L, MemoryRecords.withRecords(106L, Compression.NONE, 1,
        new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    seg.append(109L, RecordBatch.NO_TIMESTAMP, 108L, MemoryRecords.withRecords(108L, Compression.NONE, 1,
        new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    seg.append(111L, RecordBatch.NO_TIMESTAMP, 110, MemoryRecords.withRecords(110L, Compression.NONE, 2,
        new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    seg.recover(newProducerStateManager(), Optional.of(cache))
    assertEquals(java.util.Arrays.asList(new EpochEntry(0, 104L),
                             new EpochEntry(1, 106),
                             new EpochEntry(2, 110)),
      cache.epochEntries)
  }

  private def endTxnRecords(controlRecordType: ControlRecordType,
                            producerId: Long,
                            producerEpoch: Short,
                            offset: Long,
                            partitionLeaderEpoch: Int = 0,
                            coordinatorEpoch: Int = 0,
                            timestamp: Long = RecordBatch.NO_TIMESTAMP): MemoryRecords = {
    val marker = new EndTransactionMarker(controlRecordType, coordinatorEpoch)
    MemoryRecords.withEndTransactionMarker(offset, timestamp, partitionLeaderEpoch, producerId, producerEpoch, marker)
  }

  /**
   * Create a segment with some data and an index. Then corrupt the index,
   * and recover the segment, the entries should all be readable.
   */
  @Test
  def testRecoveryFixesCorruptTimeIndex(): Unit = {
    val seg = createSegment(0)
    for (i <- 0 until 100)
      seg.append(i, i * 10, i, records(i, i.toString))
    val timeIndexFile = seg.timeIndexFile
    writeNonsenseToFile(timeIndexFile, 5, timeIndexFile.length.toInt)
    seg.recover(newProducerStateManager(), Optional.empty())
    for (i <- 0 until 100) {
      assertEquals(i, seg.findOffsetByTimestamp(i * 10, 0L).get.offset)
      if (i < 99)
        assertEquals(i + 1, seg.findOffsetByTimestamp(i * 10 + 1, 0L).get.offset)
    }
  }

  /**
   * Randomly corrupt a log a number of times and attempt recovery.
   */
  @Test
  def testRecoveryWithCorruptMessage(): Unit = {
    val messagesAppended = 20
    for (_ <- 0 until 10) {
      val seg = createSegment(0)
      for (i <- 0 until messagesAppended)
        seg.append(i, RecordBatch.NO_TIMESTAMP, -1L, records(i, i.toString))
      val offsetToBeginCorruption = TestUtils.random.nextInt(messagesAppended)
      // start corrupting somewhere in the middle of the chosen record all the way to the end

      val recordPosition = seg.log.searchForOffsetWithSize(offsetToBeginCorruption, 0)
      val position = recordPosition.position + TestUtils.random.nextInt(15)
      writeNonsenseToFile(seg.log.file, position, (seg.log.file.length - position).toInt)
      seg.recover(newProducerStateManager(), Optional.empty())
      assertEquals((0 until offsetToBeginCorruption).toList, seg.log.batches.asScala.map(_.lastOffset).toList,
        "Should have truncated off bad messages.")
      seg.deleteIfExists()
    }
  }

  private def createSegment(baseOffset: Long, fileAlreadyExists: Boolean, initFileSize: Int, preallocate: Boolean): LogSegment = {
    val tempDir = TestUtils.tempDir()
    val logConfig = new LogConfig(Map(
      TopicConfig.INDEX_INTERVAL_BYTES_CONFIG -> 10,
      TopicConfig.SEGMENT_INDEX_BYTES_CONFIG -> 1000,
      TopicConfig.SEGMENT_JITTER_MS_CONFIG -> 0
    ).asJava)
    val seg = LogSegment.open(tempDir, baseOffset, logConfig, Time.SYSTEM, fileAlreadyExists, initFileSize, preallocate, "")
    segments += seg
    seg
  }

  /* create a segment with   pre allocate, put message to it and verify */
  @Test
  def testCreateWithInitFileSizeAppendMessage(): Unit = {
    val seg = createSegment(40, fileAlreadyExists = false, 512*1024*1024, preallocate = true)
    val ms = records(50, "hello", "there")
    seg.append(51, RecordBatch.NO_TIMESTAMP, -1L, ms)
    val ms2 = records(60, "alpha", "beta")
    seg.append(61, RecordBatch.NO_TIMESTAMP, -1L, ms2)
    val read = seg.read(55, 200)
    checkEquals(ms2.records.iterator, read.records.records.iterator)
  }

  /* create a segment with   pre allocate and clearly shut down*/
  @Test
  def testCreateWithInitFileSizeClearShutdown(): Unit = {
    val tempDir = TestUtils.tempDir()
    val logConfig = new LogConfig(Map(
      TopicConfig.INDEX_INTERVAL_BYTES_CONFIG -> 10,
      TopicConfig.SEGMENT_INDEX_BYTES_CONFIG -> 1000,
      TopicConfig.SEGMENT_JITTER_MS_CONFIG -> 0
    ).asJava)

    val seg = LogSegment.open(tempDir, 40, logConfig, Time.SYSTEM,
      512 * 1024 * 1024, true)

    val ms = records(50, "hello", "there")
    seg.append(51, RecordBatch.NO_TIMESTAMP, -1L, ms)
    val ms2 = records(60, "alpha", "beta")
    seg.append(61, RecordBatch.NO_TIMESTAMP, -1L, ms2)
    val read = seg.read(55, 200)
    checkEquals(ms2.records.iterator, read.records.records.iterator)
    val oldSize = seg.log.sizeInBytes()
    val oldPosition = seg.log.channel.position
    val oldFileSize = seg.log.file.length
    assertEquals(512*1024*1024, oldFileSize)
    seg.close()
    //After close, file should be trimmed
    assertEquals(oldSize, seg.log.file.length)

    val segReopen = LogSegment.open(tempDir, 40, logConfig, Time.SYSTEM, true, 512 * 1024 * 1024, true, "")
    segments += segReopen

    val readAgain = segReopen.read(55, 200)
    checkEquals(ms2.records.iterator, readAgain.records.records.iterator)
    val size = segReopen.log.sizeInBytes()
    val position = segReopen.log.channel.position
    val fileSize = segReopen.log.file.length
    assertEquals(oldPosition, position)
    assertEquals(oldSize, size)
    assertEquals(size, fileSize)
  }

  @Test
  def shouldTruncateEvenIfOffsetPointsToAGapInTheLog(): Unit = {
    val seg = createSegment(40)
    val offset = 40

    def records(offset: Long, record: String): MemoryRecords =
      MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V2, offset, Compression.NONE, TimestampType.CREATE_TIME,
        new SimpleRecord(offset * 1000, record.getBytes))

    //Given two messages with a gap between them (e.g. mid offset compacted away)
    val ms1 = records(offset, "first message")
    seg.append(offset, RecordBatch.NO_TIMESTAMP, -1L, ms1)
    val ms2 = records(offset + 3, "message after gap")
    seg.append(offset + 3, RecordBatch.NO_TIMESTAMP, -1L, ms2)

    // When we truncate to an offset without a corresponding log entry
    seg.truncateTo(offset + 1)

    //Then we should still truncate the record that was present (i.e. offset + 3 is gone)
    val log = seg.read(offset, 10000)
    assertEquals(offset, log.records.batches.iterator.next().baseOffset())
    assertEquals(1, log.records.batches.asScala.size)
  }

  @Test
  def testAppendFromFile(): Unit = {
    def records(offset: Long, size: Int): MemoryRecords =
      MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V2, offset, Compression.NONE, TimestampType.CREATE_TIME,
        new SimpleRecord(new Array[Byte](size)))

    // create a log file in a separate directory to avoid conflicting with created segments
    val tempDir = TestUtils.tempDir()
    val fileRecords = FileRecords.open(LogFileUtils.logFile(tempDir, 0))

    // Simulate a scenario where we have a single log with an offset range exceeding Int.MaxValue
    fileRecords.append(records(0, 1024))
    fileRecords.append(records(500, 1024 * 1024 + 1))
    val sizeBeforeOverflow = fileRecords.sizeInBytes()
    fileRecords.append(records(Int.MaxValue + 5L, 1024))
    val sizeAfterOverflow = fileRecords.sizeInBytes()

    val segment = createSegment(0)
    val bytesAppended = segment.appendFromFile(fileRecords, 0)
    assertEquals(sizeBeforeOverflow, bytesAppended)
    assertEquals(sizeBeforeOverflow, segment.size)

    val overflowSegment = createSegment(Int.MaxValue)
    val overflowBytesAppended = overflowSegment.appendFromFile(fileRecords, sizeBeforeOverflow)
    assertEquals(sizeAfterOverflow - sizeBeforeOverflow, overflowBytesAppended)
    assertEquals(overflowBytesAppended, overflowSegment.size)

    Utils.delete(tempDir)
  }

  @Test
  def testGetFirstBatchTimestamp(): Unit = {
    val segment = createSegment(1)
    assertEquals(Long.MaxValue, segment.getFirstBatchTimestamp)

    segment.append(1, 1000L, 1, MemoryRecords.withRecords(1, Compression.NONE, new SimpleRecord("one".getBytes)))
    assertEquals(1000L, segment.getFirstBatchTimestamp)

    segment.close()
  }

  private def newProducerStateManager(): ProducerStateManager = {
    new ProducerStateManager(
      topicPartition,
      logDir,
      5 * 60 * 1000,
      new ProducerStateManagerConfig(TransactionLogConfig.PRODUCER_ID_EXPIRATION_MS_DEFAULT, false),
      new MockTime()
    )
  }

  private def checkEquals[T](s1: java.util.Iterator[T], s2: java.util.Iterator[T]): Unit = {
    while (s1.hasNext && s2.hasNext)
      assertEquals(s1.next, s2.next)
    assertFalse(s1.hasNext, "Iterators have uneven length--first has more")
    assertFalse(s2.hasNext, "Iterators have uneven length--second has more")
  }

  private def writeNonsenseToFile(fileName: File, position: Long, size: Int): Unit = {
    val file = new RandomAccessFile(fileName, "rw")
    try {
      file.seek(position)
      for (_ <- 0 until size)
        file.writeByte(random.nextInt(255))
    } finally {
      file.close()
    }
  }

}
