/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.google.api.gax.grpc;

import com.google.api.core.ApiFunction;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.gax.longrunning.OperationSnapshot;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.grpc.Status.Code;

/** Package-private for internal use. */
public class GrpcOperationTransformers {
  private GrpcOperationTransformers() {}

  public static class ResponseTransformer<ResponseT extends Message>
      implements ApiFunction<OperationSnapshot, ResponseT> {
    private final AnyTransformer<ResponseT> transformer;

    private ResponseTransformer(Class<ResponseT> packedClass) {
      this.transformer = new AnyTransformer<>(packedClass);
    }

    @Override
    public ResponseT apply(OperationSnapshot operationSnapshot) {
      GrpcStatusCode grpcStatusCode = (GrpcStatusCode) operationSnapshot.getErrorCode();
      Code statusCode = grpcStatusCode.getCode();
      if (!statusCode.equals(Code.OK)) {
        throw GrpcApiExceptionFactory.createException(
            "Operation with name \""
                + operationSnapshot.getName()
                + "\" failed with status = "
                + statusCode,
            null,
            statusCode,
            false);
      }
      try {
        return transformer.apply((Any) operationSnapshot.getResponse());
      } catch (RuntimeException e) {
        throw GrpcApiExceptionFactory.createException(
            "Operation with name \""
                + operationSnapshot.getName()
                + "\" succeeded, but encountered a problem unpacking it.",
            e,
            statusCode,
            false);
      }
    }

    public static <ResponseT extends Message> ResponseTransformer<ResponseT> of(
        Class<ResponseT> packedClass) {
      return new ResponseTransformer<>(packedClass);
    }
  }

  public static class MetadataTransformer<MetadataT extends Message>
      implements ApiFunction<OperationSnapshot, MetadataT> {
    private final AnyTransformer<MetadataT> transformer;

    private MetadataTransformer(Class<MetadataT> packedClass) {
      this.transformer = new AnyTransformer<>(packedClass);
    }

    @Override
    public MetadataT apply(OperationSnapshot operationSnapshot) {
      try {
        return transformer.apply(
            operationSnapshot.getMetadata() != null ? (Any) operationSnapshot.getMetadata() : null);
      } catch (RuntimeException e) {
        GrpcStatusCode grpcStatusCode = (GrpcStatusCode) operationSnapshot.getErrorCode();
        Code statusCode = grpcStatusCode.getCode();
        throw GrpcApiExceptionFactory.createException(
            "Polling operation with name \""
                + operationSnapshot.getName()
                + "\" succeeded, but encountered a problem unpacking it.",
            e,
            statusCode,
            false);
      }
    }

    public static <ResponseT extends Message> MetadataTransformer<ResponseT> of(
        Class<ResponseT> packedClass) {
      return new MetadataTransformer<>(packedClass);
    }
  }

  static class AnyTransformer<PackedT extends Message> implements ApiFunction<Any, PackedT> {
    private final Class<PackedT> packedClass;

    public AnyTransformer(Class<PackedT> packedClass) {
      this.packedClass = packedClass;
    }

    @Override
    public PackedT apply(Any input) {
      try {
        return input == null || packedClass == null ? null : input.unpack(packedClass);
      } catch (InvalidProtocolBufferException | ClassCastException e) {
        throw new IllegalStateException(
            "Failed to unpack object from 'any' field. Expected "
                + packedClass.getName()
                + ", found "
                + input.getTypeUrl());
      }
    }
  }

  public static class StartOperationCallable<RequestT>
      extends UnaryCallable<RequestT, OperationSnapshot> {

    private final UnaryCallable<RequestT, Operation> innerUnaryCallable;

    private StartOperationCallable(UnaryCallable<RequestT, Operation> innerUnaryCallable) {
      this.innerUnaryCallable = innerUnaryCallable;
    }

    @Override
    public ApiFuture<OperationSnapshot> futureCall(RequestT request, ApiCallContext context) {
      return ApiFutures.transform(
          innerUnaryCallable.futureCall(request, context), new OperationTransformer());
    }

    private static class OperationTransformer implements ApiFunction<Operation, OperationSnapshot> {
      @Override
      public OperationSnapshot apply(Operation operation) {
        return GrpcOperationSnapshot.create(operation);
      }
    }

    public static <RequestT> StartOperationCallable<RequestT> of(
        UnaryCallable<RequestT, Operation> innerUnaryCallable) {
      return new StartOperationCallable<>(innerUnaryCallable);
    }
  }
}
