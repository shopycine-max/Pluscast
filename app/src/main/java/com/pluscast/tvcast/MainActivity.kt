        // Initialize Local Server safely
        try {
            localServer = LocalMediaServer(this, 8080)
            localServer.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
