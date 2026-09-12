class ErrorCodes {
  static const int invalidArguments = 400;
  static const int notFound = 404;
  /// The native atomic transaction was rejected without committing changes.
  static const int atomicWriteRejected = 422;
  static const int platformSpecific = 599;
  static const int generic = 500;
  static const int unknown = 502;
}
