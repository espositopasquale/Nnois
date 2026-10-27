package com.nnois.nlp;

import java.util.List;
import com.nnois.nlp.wsd.OccurrenceContext;

@FunctionalInterface
public interface OmwSenseModel extends AutoCloseable {
    double[] score(OccurrenceContext context, List<String> definitions) throws Exception;

    @Override
    default void close() throws Exception { }
}
