package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** 원본 다운로드의 Range 가 파일 크기를 벗어남. */
public class RangeNotSatisfiableException extends DomainException {

    public RangeNotSatisfiableException() {
        super("RANGE_NOT_SATISFIABLE", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Range Not Satisfiable",
                "요청한 범위가 파일 크기를 벗어납니다.");
    }
}
