        // Initialize Google Cast safely
        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Cast service available nahi hai, baaki features chalenge", Toast.LENGTH_SHORT).show()
        }

        // Initialize Local Server safely
        try {
            localServer = LocalMediaServer(this, 8080)
            localServer.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
