package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PerpService {

    private final PerpInstrumentRepository instrumentRepo;

    public List<PerpInstrument> getTop10High(String exchange) {
        return instrumentRepo.findTop10HighByExchange(exchange);
    }

    public List<PerpInstrument> getTop10Low(String exchange) {
        return instrumentRepo.findTop10LowByExchange(exchange);
    }

    public long getInstrumentCount(String exchange) {
        return instrumentRepo.countByExchangeAndIsActiveTrue(exchange);
    }
}
