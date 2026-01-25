<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Verification Code</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            background-color: #f4f4f4;
            margin: 0;
            padding: 0;
        }
        .container {
            max-width: 600px;
            margin: 40px auto;
            background-color: #ffffff;
            border-radius: 8px;
            overflow: hidden;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .header {
            background-color: #f7d686;
            padding: 30px;
            text-align: center;
        }
        .header h1 {
            margin: 0;
            color: #000000;
            font-size: 28px;
        }
        .content {
            padding: 40px 30px;
        }
        .otp-box {
            background-color: #f7d686;
            border-radius: 8px;
            padding: 20px;
            text-align: center;
            margin: 30px 0;
        }
        .otp-code {
            font-size: 36px;
            font-weight: bold;
            letter-spacing: 8px;
            color: #000000;
        }
        .message {
            color: #666666;
            line-height: 1.6;
            font-size: 16px;
        }
        .footer {
            background-color: #f9f9f9;
            padding: 20px;
            text-align: center;
            color: #999999;
            font-size: 12px;
        }
    </style>
</head>
<body>
<div class="container">
    <div class="header">
        <h1>WALKSENSE</h1>
    </div>
    <div class="content">
        <p class="message">Hello {{ $userName }},</p>
        <p class="message">Thank you for registering with WalkSense! Please use the verification code below to complete your registration:</p>

        <div class="otp-box">
            <div class="otp-code">{{ $otp }}</div>
        </div>

        <p class="message">This code will expire in <strong>2 minutes</strong>.</p>
        <p class="message">If you didn't request this code, please ignore this email.</p>
    </div>
    <div class="footer">
        <p>&copy; 2026 WalkSense. All rights reserved.</p>
    </div>
</div>
</body>
</html>
