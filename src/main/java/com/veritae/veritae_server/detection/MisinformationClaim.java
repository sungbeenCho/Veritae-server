package com.veritae.veritae_server.detection;

import java.util.List;

/** 거짓(반박)으로 판정된 문장 하나. 참/판단불가로 나온 문장은 애초에 이 타입으로
 * 만들어지지 않는다 - misinfo_infer.py가 반박만 결과에 담아 보낸다. */
public record MisinformationClaim(String sentence, String reason, List<WikiEvidence> evidence) {
}
