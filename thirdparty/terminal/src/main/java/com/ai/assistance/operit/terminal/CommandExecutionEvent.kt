package com.ai.assistance.operit.terminal

import android.os.Parcel
import android.os.Parcelable

data class CommandExecutionEvent(
    val commandId: String,
    val sessionId: String,
    val outputChunk: String, // 命令执行过程量
    val isCompleted: Boolean // 是否执行完毕
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(commandId)
        dest.writeString(sessionId)
        dest.writeString(outputChunk)
        dest.writeInt(if (isCompleted) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<CommandExecutionEvent> {
        override fun createFromParcel(parcel: Parcel): CommandExecutionEvent = CommandExecutionEvent(
            commandId = parcel.readString().orEmpty(),
            sessionId = parcel.readString().orEmpty(),
            outputChunk = parcel.readString().orEmpty(),
            isCompleted = parcel.readInt() != 0
        )

        override fun newArray(size: Int): Array<CommandExecutionEvent?> = arrayOfNulls(size)
    }
}

data class SessionDirectoryEvent(
    val sessionId: String,
    val currentDirectory: String
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(sessionId)
        dest.writeString(currentDirectory)
    }

    companion object CREATOR : Parcelable.Creator<SessionDirectoryEvent> {
        override fun createFromParcel(parcel: Parcel): SessionDirectoryEvent = SessionDirectoryEvent(
            sessionId = parcel.readString().orEmpty(),
            currentDirectory = parcel.readString().orEmpty()
        )

        override fun newArray(size: Int): Array<SessionDirectoryEvent?> = arrayOfNulls(size)
    }
}