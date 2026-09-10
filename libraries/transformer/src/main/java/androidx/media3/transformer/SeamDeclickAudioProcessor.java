/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * An {@link AudioProcessor} that applies short fade-in/fade-out ramps at stream boundaries to
 * eliminate audible clicks caused by decoder flush/restart transients when concatenating
 * independently decoded audio segments.
 *
 * <p>At the start of each stream (after {@link #flush}), the first {@code declickDurationUs} of
 * audio is linearly faded in from silence. At the end of each stream (after {@link
 * #queueEndOfStream}), the last {@code declickDurationUs} of audio is linearly faded out to
 * silence.
 *
 * <p>To implement the fade-out without knowing the stream length in advance, the processor
 * maintains a tail buffer that delays output by {@code declickDurationUs}. This delay (~5ms by
 * default) is imperceptible.
 */
/* package */ final class SeamDeclickAudioProcessor implements AudioProcessor {

  private static final int DEFAULT_DECLICK_DURATION_US = 10_000; // 10ms

  private final int declickDurationUs;

  private AudioFormat audioFormat;
  private AudioFormat pendingAudioFormat;

  private int declickFrameCount;
  private int tailBufferSizeBytes;

  private ByteBuffer tailBuffer;
  private int tailBufferValidBytes;

  private ByteBuffer outputBuffer;

  private long framesOutputSinceFlush;
  private boolean inputEnded;

  public SeamDeclickAudioProcessor() {
    this(DEFAULT_DECLICK_DURATION_US);
  }

  public SeamDeclickAudioProcessor(int declickDurationUs) {
    this.declickDurationUs = declickDurationUs;
    audioFormat = AudioFormat.NOT_SET;
    pendingAudioFormat = AudioFormat.NOT_SET;
    tailBuffer = EMPTY_BUFFER;
    outputBuffer = EMPTY_BUFFER;
  }

  @Override
  public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
    if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    pendingAudioFormat = inputAudioFormat;
    return inputAudioFormat;
  }

  @Override
  public boolean isActive() {
    return !pendingAudioFormat.equals(AudioFormat.NOT_SET);
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    if (!inputBuffer.hasRemaining()) {
      return;
    }
    checkState(!inputEnded);

    int bytesPerFrame = audioFormat.bytesPerFrame;

    applyFadeIn(inputBuffer, bytesPerFrame);

    if (tailBufferSizeBytes == 0) {
      int inputBytes = inputBuffer.remaining();
      ensureOutputCapacity(inputBytes);
      outputBuffer.put(inputBuffer);
      outputBuffer.flip();
      return;
    }

    int inputBytesRemaining = inputBuffer.remaining();

    if (tailBufferValidBytes < tailBufferSizeBytes && inputBytesRemaining > 0) {
      int spaceInTail = tailBufferSizeBytes - tailBufferValidBytes;
      int bytesToFillTail = Math.min(spaceInTail, inputBytesRemaining);
      int oldLimit = inputBuffer.limit();
      inputBuffer.limit(inputBuffer.position() + bytesToFillTail);
      tailBuffer.position(tailBufferValidBytes);
      tailBuffer.put(inputBuffer);
      inputBuffer.limit(oldLimit);
      tailBufferValidBytes += bytesToFillTail;
      inputBytesRemaining = inputBuffer.remaining();

      if (inputBytesRemaining == 0) {
        return;
      }
    }

    int displacedBytes = Math.min(inputBytesRemaining, tailBufferValidBytes);
    int passThroughBytes = Math.max(0, inputBytesRemaining - tailBufferSizeBytes);
    ensureOutputCapacity(displacedBytes + passThroughBytes);

    tailBuffer.position(0);
    tailBuffer.limit(displacedBytes);
    outputBuffer.put(tailBuffer);

    int remainingTailBytes = tailBufferValidBytes - displacedBytes;
    if (remainingTailBytes > 0) {
      tailBuffer.limit(tailBufferValidBytes);
      tailBuffer.position(displacedBytes);
      ByteBuffer remaining = tailBuffer.slice();
      tailBuffer.position(0);
      tailBuffer.put(remaining);
    }

    if (passThroughBytes > 0) {
      int inputPassThroughEnd = inputBuffer.limit() - tailBufferSizeBytes;
      int savedLimit = inputBuffer.limit();
      inputBuffer.limit(inputPassThroughEnd);
      outputBuffer.put(inputBuffer);
      inputBuffer.limit(savedLimit);
    }

    int newTailBytes = Math.min(inputBytesRemaining, tailBufferSizeBytes);
    int newTailStart = inputBuffer.limit() - newTailBytes;
    inputBuffer.position(newTailStart);
    tailBuffer.position(tailBufferSizeBytes - newTailBytes);
    tailBuffer.put(inputBuffer);
    tailBufferValidBytes = tailBufferSizeBytes;

    outputBuffer.flip();
    framesOutputSinceFlush += (displacedBytes + passThroughBytes) / bytesPerFrame;
  }

  @Override
  public void queueEndOfStream() {
    inputEnded = true;
    if (tailBufferValidBytes > 0) {
      applyFadeOut(tailBuffer, tailBufferValidBytes, audioFormat.bytesPerFrame);
      ensureOutputCapacity(tailBufferValidBytes);
      tailBuffer.position(0);
      tailBuffer.limit(tailBufferValidBytes);
      outputBuffer.put(tailBuffer);
      outputBuffer.flip();
      framesOutputSinceFlush += tailBufferValidBytes / audioFormat.bytesPerFrame;
      tailBufferValidBytes = 0;
    }
  }

  @Override
  public ByteBuffer getOutput() {
    ByteBuffer result = outputBuffer;
    outputBuffer = EMPTY_BUFFER;
    return result;
  }

  @Override
  public boolean isEnded() {
    return inputEnded && !outputBuffer.hasRemaining() && tailBufferValidBytes == 0;
  }

  @Override
  public void flush(StreamMetadata streamMetadata) {
    audioFormat = pendingAudioFormat;
    if (!audioFormat.equals(AudioFormat.NOT_SET)) {
      declickFrameCount =
          (int) Util.durationUsToSampleCount(declickDurationUs, audioFormat.sampleRate);
      tailBufferSizeBytes = declickFrameCount * audioFormat.bytesPerFrame;
      if (tailBuffer.capacity() < tailBufferSizeBytes) {
        tailBuffer = ByteBuffer.allocateDirect(tailBufferSizeBytes).order(ByteOrder.nativeOrder());
      } else {
        tailBuffer.clear();
        tailBuffer.limit(tailBufferSizeBytes);
      }
    } else {
      declickFrameCount = 0;
      tailBufferSizeBytes = 0;
    }
    tailBufferValidBytes = 0;
    outputBuffer = EMPTY_BUFFER;
    framesOutputSinceFlush = 0;
    inputEnded = false;
  }

  @Override
  public void reset() {
    audioFormat = AudioFormat.NOT_SET;
    pendingAudioFormat = AudioFormat.NOT_SET;
    declickFrameCount = 0;
    tailBufferSizeBytes = 0;
    tailBuffer = EMPTY_BUFFER;
    tailBufferValidBytes = 0;
    outputBuffer = EMPTY_BUFFER;
    framesOutputSinceFlush = 0;
    inputEnded = false;
  }

  private void applyFadeIn(ByteBuffer buffer, int bytesPerFrame) {
    if (declickFrameCount == 0) {
      return;
    }

    long firstFrameIndex = framesOutputSinceFlush + (tailBufferValidBytes / bytesPerFrame);
    if (firstFrameIndex >= declickFrameCount) {
      return;
    }

    int channelCount = audioFormat.channelCount;
    int bytesPerSample = bytesPerFrame / channelCount;
    boolean is16Bit = audioFormat.encoding == C.ENCODING_PCM_16BIT;

    int pos = buffer.position();
    int limit = buffer.limit();
    int frameCount = (limit - pos) / bytesPerFrame;

    for (int f = 0; f < frameCount; f++) {
      long globalFrame = firstFrameIndex + f;
      if (globalFrame >= declickFrameCount) {
        break;
      }
      // Equal-power cosine curve: stronger attenuation near the boundary (frame 0).
      float t = (float) globalFrame / declickFrameCount;
      float gain = (float) Math.sin(t * Math.PI / 2.0);
      int frameOffset = pos + f * bytesPerFrame;
      applySampleGain(buffer, frameOffset, channelCount, bytesPerSample, is16Bit, gain);
    }
  }

  private void applyFadeOut(ByteBuffer buffer, int validBytes, int bytesPerFrame) {
    int frameCount = validBytes / bytesPerFrame;
    if (frameCount == 0 || declickFrameCount == 0) {
      return;
    }

    int channelCount = audioFormat.channelCount;
    int bytesPerSample = bytesPerFrame / channelCount;
    boolean is16Bit = audioFormat.encoding == C.ENCODING_PCM_16BIT;

    for (int f = 0; f < frameCount; f++) {
      // Equal-power cosine curve: stronger attenuation near the end (last frame).
      float t = (float) (f + 1) / frameCount;
      float gain = (float) Math.cos(t * Math.PI / 2.0);
      int frameOffset = f * bytesPerFrame;
      applySampleGain(buffer, frameOffset, channelCount, bytesPerSample, is16Bit, gain);
    }
  }

  private static void applySampleGain(
      ByteBuffer buffer,
      int frameOffset,
      int channelCount,
      int bytesPerSample,
      boolean is16Bit,
      float gain) {
    for (int c = 0; c < channelCount; c++) {
      int sampleOffset = frameOffset + c * bytesPerSample;
      if (is16Bit) {
        short sample = buffer.getShort(sampleOffset);
        buffer.putShort(sampleOffset, (short) Math.round(sample * gain));
      } else {
        float sample = buffer.getFloat(sampleOffset);
        buffer.putFloat(sampleOffset, sample * gain);
      }
    }
  }

  private ByteBuffer outputBackingBuffer = EMPTY_BUFFER;

  private void ensureOutputCapacity(int requiredBytes) {
    if (!outputBuffer.hasRemaining() || outputBuffer == EMPTY_BUFFER) {
      if (outputBackingBuffer.capacity() < requiredBytes) {
        outputBackingBuffer =
            ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder());
      } else {
        outputBackingBuffer.clear();
      }
      outputBuffer = outputBackingBuffer;
    }
  }
}
