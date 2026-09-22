# The game with a virtual screen and software rendering: crashes that only happen while the world is drawn
# need a real render loop, which a hidden window does not give.
FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb libgl1 libgl1-mesa-dri libglx-mesa0 libxrandr2 libxcursor1 libxi6 libxxf86vm1 libxtst6 libxrender1 \
    libfreetype6 libfontconfig1 && rm -rf /var/lib/apt/lists/*
