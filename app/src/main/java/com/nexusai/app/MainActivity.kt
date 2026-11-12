
    // VOICE ENGINE START

    fun startVoiceService() {

        try {

            val voiceIntent = Intent(
                context,
                NexusVoiceService::class.java
            ).apply {

                action = NexusVoiceService.ACTION_START

                putExtra(
                    NexusVoiceService.EXTRA_LANGUAGE,
                    if (sinhala) "si-LK" else "en-US"
                )
            }

            ContextCompat.startForegroundService(
                context,
                voiceIntent
            )

            voiceEngineEnabled = true
            voicePermissionDenied = false

        } catch (e: SecurityException) {

            voiceEngineEnabled = false

            Toast.makeText(
                context,
                "Voice permission error: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            voiceEngineEnabled = false

            Toast.makeText(
                context,
                "Voice Engine Error: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // MICROPHONE PERMISSION

    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startVoiceService()

            } else {

                voiceEngineEnabled = false
                voicePermissionDenied = true

                Toast.makeText(
                    context,
                    "Microphone permission required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // VOICE ENGINE CONTROL

    fun startVoiceEngine() {

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {

            startVoiceService()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    fun stopVoiceEngine() {

        try {

            context.stopService(
                Intent(
                    context,
                    NexusVoiceService::class.java
                )
            )

            voiceEngineEnabled = false

        } catch (e: Exception) {

            Toast.makeText(
                context,
                "Voice Engine Stop Error: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
