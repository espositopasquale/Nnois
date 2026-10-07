package com.nnois.nlp;

import java.util.List;

/** Scores only supplied OMW candidates, preserving their offset and POS. */
@FunctionalInterface
public interface OmwSenseModel {
    double[] score(String context, List<String> definitions) throws Exception;
}
