-- Agendamento DIÁRIO da atualização automática (rode DEPOIS de criar a função world-sync). Troque os dois valores entre <>:
--   <PROJECT_REF>  = o trecho antes de .supabase.co na sua Project URL (ex.: mpsfooytlswujoqzkppg)
--   <ANON_KEY>     = a chave "anon"/publishable (a MESMA que já está no app; é pública). NUNCA use a service_role aqui.
create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.unschedule('world-sync-daily') where exists (select 1 from cron.job where jobname = 'world-sync-daily');
select cron.schedule('world-sync-daily', '17 5 * * *', $$
  select net.http_post(
    url := 'https://<PROJECT_REF>.supabase.co/functions/v1/world-sync',
    headers := jsonb_build_object('Content-Type', 'application/json', 'Authorization', 'Bearer <ANON_KEY>', 'apikey', '<ANON_KEY>'),
    body := '{}'::jsonb, timeout_milliseconds := 150000);
$$);
-- Conferir: select jobname, schedule, active from cron.job;   •   histórico: select status, return_message, start_time from cron.job_run_details order by start_time desc limit 5;
-- Cada execução faz um pedaço (2 países de clubes + 2 de jogadores + fila da API-Football) e retoma de onde parou: a base cresce todo dia, sem você fazer nada.
