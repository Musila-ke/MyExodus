package com.example.myexodus.data.db

/** Thrown when a write attempts to block the app's own package (Rule 4). */
class ProtectedPackageException : RuntimeException(
    "The app's own package can never be a blocked package",
)
