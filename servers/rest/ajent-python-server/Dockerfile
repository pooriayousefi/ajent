# Use a slim Python base image
FROM python:3.11-slim

# Set the working directory inside the container
WORKDIR /app

# Install system dependencies, wget (to download fonts), and fontconfig
RUN apt-get update && apt-get install -y \
    build-essential \
    libffi-dev \
    wget \
    fontconfig \
    && rm -rf /var/lib/apt/lists/*

# 1. Download and install Vazirmatn (Open-source Persian font)
RUN mkdir -p /usr/share/fonts/truetype/vazirmatn && \
    wget -qO /usr/share/fonts/truetype/vazirmatn/Vazirmatn.ttf https://github.com/rastikerdar/vazirmatn/raw/master/fonts/ttf/Vazirmatn-Regular.ttf && \
    fc-cache -f -v

# 2. Copy your local custom fonts (like B Nazanin) into the container
COPY fonts/ /usr/share/fonts/truetype/local/
RUN fc-cache -f -v

# Install all your requested Python libraries + Persian RTL support
RUN pip install --no-cache-dir \
    numpy \
    pandas \
    scipy \
    matplotlib \
    pymupdf \
    markdown \
    python-docx \
    python-pptx \
    openpyxl \
    requests \
    beautifulsoup4 \
    arabic-reshaper \
    python-bidi

# Copy the REST server script into the container
COPY server.py .

# Expose the port the server runs on
EXPOSE 4007

# Run the server
CMD ["python3", "server.py"]