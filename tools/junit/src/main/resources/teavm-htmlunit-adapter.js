function runMain(callback) {
    main([], function(result) {
        var message = {};
        if (result instanceof Error) {
            makeErrorMessage(message, result);
        } else {
            message.status = "ok";
        }
        callback.complete(message);
    });

    function makeErrorMessage(message, e) {
        message.status = "exception";
        if (e.$javaException && e.$javaException.constructor.$meta) {
            message.className = e.$javaException.constructor.name;
            message.message = e.$javaException.getMessage();
        }
        message.exception = e;
        message.stack = e.stack;
    }
}