package org.kc7bfi.jflac.frame;

/**
 * libFLAC - Free Lossless Audio Codec library
 * Copyright (C) 2001,2002,2003  Josh Coalson
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA  02111-1307, USA.
 */

import java.io.IOException;

import org.kc7bfi.jflac.ChannelData;
import org.kc7bfi.jflac.Constants;
import org.kc7bfi.jflac.LPCPredictor;
import org.kc7bfi.jflac.util.BitMath;
import org.kc7bfi.jflac.util.InputBitStream;

public class ChannelLPC extends ChannelBase {
    private static final int SUBFRAME_LPC_QLP_COEFF_PRECISION_LEN = 4; /* bits */
    private static final int SUBFRAME_LPC_QLP_SHIFT_LEN = 5; /* bits */
    private static final int MAX_LPC_ORDER = 32;

    private EntropyCodingMethod entropyCodingMethod; // The residual coding method.
    private int order; // The FIR order.
    private int qlpCoeffPrecision; // Quantized FIR filter coefficient precision in bits.
    private int quantizationLevel; // The qlp coeff shift needed.
    private int[] qlpCoeff = new int[MAX_LPC_ORDER]; // FIR filter coefficients.
    private int[] warmup = new int[MAX_LPC_ORDER]; // Warmup samples to prime the predictor, length == order.
    private int[] residual; // The residual signal, length == (blocksize minus order) samples.

    /**
     * The constructor.
     * @param is            The InputBitStream
     * @param header        The FLAC Frame Header
     * @param channelData   The decoded channel data (output)
     * @param bps           The bits-per-second
     * @param wastedBits    The bits waisted in the frame
     * @param order         The predicate order
     * @throws IOException  Thrown if error reading from the InputBitStream
     */
    public ChannelLPC(InputBitStream is, Header header, ChannelData channelData, int bps, int wastedBits, int order) throws IOException {
        super(header, wastedBits);

        this.residual = channelData.residual;
        this.order = order;

        // read warm-up samples
        for (int u = 0; u < order; u++) {
            warmup[u] = is.readRawInt(bps);
        }

        // read qlp coeff precision
        int u32 = is.readRawUInt(SUBFRAME_LPC_QLP_COEFF_PRECISION_LEN);
        if (u32 == (1 << SUBFRAME_LPC_QLP_COEFF_PRECISION_LEN) - 1) {
            throw new IOException("STREAM_DECODER_ERROR_STATUS_LOST_SYNC");
        }
        qlpCoeffPrecision = u32 + 1;

        // read qlp shift
        quantizationLevel = is.readRawInt(SUBFRAME_LPC_QLP_SHIFT_LEN);

        // read quantized lp coefficiencts
        for (int u = 0; u < order; u++) {
            qlpCoeff[u] = is.readRawInt(qlpCoeffPrecision);
        }

        // read entropy coding method info
        int codingType = is.readRawUInt(Constants.ENTROPY_CODING_METHOD_TYPE_LEN);
        switch (codingType) {
            case Constants.ENTROPY_CODING_METHOD_PARTITIONED_RICE :
                ((EntropyPartitionedRice) entropyCodingMethod).order = is.readRawUInt(Constants.ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN);
                ((EntropyPartitionedRice) entropyCodingMethod).contents = channelData.partitionedRiceContents;
                break;
            default :
                throw new IOException("STREAM_DECODER_UNPARSEABLE_STREAM");
        }
 
        // read residual
        if (entropyCodingMethod instanceof EntropyPartitionedRice) {
            ((EntropyPartitionedRice) entropyCodingMethod).readResidual(is, 
                order,
                ((EntropyPartitionedRice) entropyCodingMethod).order,
                header,
                channelData.residual);
        }

        // decode the subframe
        System.arraycopy(warmup, 0, channelData.output, 0, order);
        if (bps + qlpCoeffPrecision + BitMath.ilog2(order) <= 32) {
            if (bps <= 16 && qlpCoeffPrecision <= 16)
                LPCPredictor.restoreSignal(channelData.residual, header.blockSize - order, qlpCoeff, order, quantizationLevel, channelData.output, order);
            else
                LPCPredictor.restoreSignal(channelData.residual, header.blockSize - order, qlpCoeff, order, quantizationLevel, channelData.output, order);
        } else {
            LPCPredictor.restoreSignalWide(channelData.residual, header.blockSize - order, qlpCoeff, order, quantizationLevel, channelData.output, order);
        }
    }
    
    /**
     * @see java.lang.Object#toString()
     */
    public String toString() {
        return "FLACSubframe_LPC: WastedBits=" + wastedBits;
    }

}
