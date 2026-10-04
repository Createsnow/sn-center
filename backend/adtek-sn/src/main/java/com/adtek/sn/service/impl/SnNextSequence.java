package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.mapper.IncomingSnMapper;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.NextSequenceVO;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;

/** 按当前编码严格反解当前PI下转入的号；不改原流水归属，不逐个跳过冲突，不在转厂时推进水位。 */
@Component
public class SnNextSequence {
    private final IncomingSnMapper rows;
    private final SnWorkSettings settings;

    public SnNextSequence(IncomingSnMapper rows, SnWorkSettings settings) {
        this.rows = rows;
        this.settings = settings;
    }

    record Position(long ownLast, Long incomingMax, String incomingSn) {
        long base() {
            return incomingMax == null ? ownLast : Math.max(ownLast, incomingMax);
        }

        NextSequenceVO view(SequenceSpec spec) {
            BigInteger start = BigInteger.valueOf(base()).add(BigInteger.ONE);
            boolean adjusted = incomingMax != null && incomingMax > ownLast;
            return new NextSequenceVO(
                    Long.toString(ownLast),
                    start.toString(),
                    base() >= SequenceCodec.capacity(spec)
                            ? null
                            : SequenceCodec.format(base() + 1, spec),
                    adjusted,
                    adjusted ? incomingMax.toString() : null,
                    adjusted ? incomingSn : null);
        }

        void requireAboveIncoming(long start) {
            if (incomingMax != null && start <= incomingMax)
                throw new BusinessException(
                        START_BEFORE_INCOMING,
                        Map.of("sn", incomingSn, "sequence", incomingMax.toString()));
        }
    }

    Position current(String pi, long ownLast, SequenceSpec spec) {
        Long max = null;
        String match = null, after = null;
        int count = 0;
        int length =
                spec.prefix().codePointCount(0, spec.prefix().length())
                        + spec.length()
                        + spec.suffix().codePointCount(0, spec.suffix().length());
        while (true) {
            var page = rows.page(pi, length, after);
            if (page.isEmpty()) break;
            count = Math.addExact(count, page.size());
            if (count > settings.getMaxIncomingScan()) throw new BusinessException(RESOURCE_LIMIT);
            for (String sn : page) {
                var sequence = SequenceCodec.parse(sn, spec);
                if (sequence.isPresent() && (max == null || sequence.getAsLong() > max)) {
                    max = sequence.getAsLong();
                    match = sn;
                }
            }
            after = page.getLast();
        }
        return new Position(ownLast, max, match);
    }
}
