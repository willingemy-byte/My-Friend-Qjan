FROM python:3.12.11-slim
WORKDIR /app
COPY licensing/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY licensing /app/licensing
COPY supabase/setup /app/supabase/setup
COPY supabase/personal/schema-v1.sql /app/supabase/personal/schema-v1.sql
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["python", "licensing/server.py"]
