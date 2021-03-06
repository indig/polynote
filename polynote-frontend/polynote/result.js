'use strict';

import {
    Codec, DataReader, DataWriter, discriminated, combined, arrayCodec,
    str, shortStr, tinyStr, uint8, uint16, int32
} from './codec.js'

export class Result {
    static decode(data) {
        return Codec.decode(Result.codec, data);
    }

    static encode(msg) {
        return Codec.encode(Result.codec, msg);
    }

    encode() {
        return Message.encode(this);
    }
}

export class Output extends Result {
    static get msgTypeId() { return 0; }

    static unapply(inst) {
        return [inst.contentType, inst.content];
    }

    constructor(contentType, content) {
        super(contentType, content);
        this.contentType = contentType;
        this.content = content;
        Object.freeze(this);
    }
}

Output.codec = combined(str, str).to(Output);

export class Position {
    static unapply(inst) {
        return [inst.source, inst.start, inst.end, inst.point];
    }

    constructor(source, start, end, point) {
        this.source = source;
        this.start = start;
        this.end = end;
        this.point = point;
        Object.freeze(this);
    }
}

Position.codec = combined(str, int32, int32, int32).to(Position);

export class KernelReport {
    static unapply(inst) {
        return [inst.position, inst.message, inst.severity];
    }

    constructor(position, message, severity) {
        this.position = position;
        this.message = message;
        this.severity = severity;
        Object.freeze(this);
    }

    get isError() {
        return this.severity === 2;
    }
}

KernelReport.codec = combined(Position.codec, str, int32).to(KernelReport);

export class CompileErrors extends Result {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.reports];
    }

    constructor(reports) {
        super(reports);
        this.reports = reports;
        Object.freeze(this);
    }
}

CompileErrors.codec = combined(arrayCodec(int32, KernelReport.codec)).to(CompileErrors);

// maps to JVM stack trace element
export class StackTraceElement {
    static unapply(inst) {
        return [inst.className, inst.method, inst.file, inst.line];
    }

    constructor(className, method, file, line) {
        this.className = className;
        this.method = method;
        this.file = file;
        this.line = line;
        Object.freeze(this);
    }
}

StackTraceElement.codec = combined(str, str, str, int32).to(StackTraceElement);

// maps to JVM Throwable
// WARNING: not frozen (mutable)
export class KernelError {
    static unapply(inst) {
        return [inst.className, inst.message, inst.stackTrace];
    }

    constructor(className, message, stackTrace) {
        this.className = className;
        this.message = message;
        this.stackTrace = stackTrace;
    }
}

KernelError.codec = combined(str, str, arrayCodec(uint16, StackTraceElement.codec)).to(KernelError);

export class KernelErrorWithCause extends KernelError {
    static unapply(inst) {
        return [inst.className, inst.message, inst.stackTrace, inst.cause];
    }

    constructor(className, message, stackTrace, cause) {
        super(className, message, stackTrace, cause);
        this.cause = cause;
        Object.freeze(this);
    }
}

KernelErrorWithCause.codec = Codec.map(
    arrayCodec(uint8, KernelError.codec),
    (kernelErrors) => {
        if (kernelErrors.length === 0) return null;

        var i = kernelErrors.length - 1;
        var current = new KernelErrorWithCause(kernelErrors[i].className, kernelErrors[i].message, kernelErrors[i].stackTrace, null);

        while (i > 0) {
            const next = kernelErrors[--i];
            current = new KernelErrorWithCause(next.className, next.message, next.stackTrace, current);
        }
        return current;
    },
    (withCause) => {
        if (withCause == null) return null;
        const errs = [];
        var current = withCause;
        var i = 0;
        while (i < 16 && current != null) {
            errs.push(new KernelError(current.className, current.message, current.stackTrace));
            current = current.cause;
            i++;
        }

        return errs;
    }
);

export class RuntimeError extends Result {
    static get msgTypeId() { return 2; }

    static unapply(inst) {
        return [inst.error];
    }

    constructor(error) {
        super(error);
        this.error = error;
        Object.freeze(this);
    }
}

RuntimeError.codec = combined(KernelErrorWithCause.codec).to(RuntimeError);

Result.codecs = [
  Output,
  CompileErrors,
  RuntimeError,
];

Result.codec = discriminated(
    uint8,
    (msgTypeId) => Result.codecs[msgTypeId].codec,
    (result) => result.constructor.msgTypeId
);


