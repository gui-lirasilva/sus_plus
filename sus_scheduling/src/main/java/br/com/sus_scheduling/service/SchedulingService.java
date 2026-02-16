package br.com.sus_scheduling.service;

import br.com.sus_scheduling.config.CacheType;
import br.com.sus_scheduling.controller.dto.scheduling.*;
import br.com.sus_scheduling.model.Scheduling;
import br.com.sus_scheduling.queue.consumer.dto.*;
import br.com.sus_scheduling.queue.producer.MessageProducer;
import br.com.sus_scheduling.queue.producer.dto.*;
import br.com.sus_scheduling.repository.SchedulingRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static br.com.sus_scheduling.config.CacheType.PATIENT;
import static br.com.sus_scheduling.config.CacheType.PROFESSIONAL;
import static br.com.sus_scheduling.model.SchedulingStatus.*;

@Service
public class SchedulingService {

    private final SchedulingRepository schedulingRepository;
    private final MessageProducer messageProducer;
    private final CacheService cacheService;
    private final Logger logger = Logger.getLogger(SchedulingService.class.getName());

    public SchedulingService(SchedulingRepository schedulingRepository, MessageProducer messageProducer, CacheService cacheService) {
        this.schedulingRepository = schedulingRepository;
        this.messageProducer = messageProducer;
        this.cacheService = cacheService;
    }

    @Transactional
    public SchedulingDTO register(SchedulingFormDTO formDTO) {
        Scheduling scheduling = schedulingRepository.save(new Scheduling(formDTO));
        messageProducer.sendToIntegrated(new MessageBodyForIntegrated(formDTO.patientId(), scheduling.getId()));
        // TODO: Caso não seja possível agendar, retornar aviso ao paciente e enviar via messageria para serviço externo que utiliza IA para sugerir encaminhamento para outra unidade mais próxima com profissional disponível
        // TODO: enviar menssageria para serviço de paciente para validar id (ou documento) e retornar nome
        //TODO: mandar email via serviço externo
        logger.info("New appointment: " + scheduling);
        return new SchedulingDTO(scheduling);
    }

    public void postValidateIntegrated(MessageBodyByIntegrated message) {
        Scheduling scheduling = findById(message.schedulingId());
        if (message.validatedPatient()) {
            messageProducer.sendToUnity(new MessageBodyForUnity(scheduling.getSpecialityId(),
                    scheduling.getUnityId(),
                    scheduling.getProfessionalId(),
                    scheduling.getAppointment(),
                    scheduling.getId()));
        } else {
            cancelScheduling(message.schedulingId());
            logger.info("Sending email to external service for scheduling: " + message.schedulingId());
        }
    }

    public void postValidateUnity(MessageBodyByUnity message) {
        Scheduling scheduling = findById(message.schedulingId());
        if (message.isValidated()) {
            scheduling.approve();
            schedulingRepository.save(scheduling);
        } else {
            cancelScheduling(message.schedulingId());
            logger.info("Sending email to external service for scheduling: " + message.schedulingId());
        }
    }

    private void cancelScheduling(UUID schedulingId) {
        Scheduling scheduling = findById(schedulingId);
        scheduling.cancel();
        schedulingRepository.save(scheduling);
        logger.info("Canceled appointment: " + scheduling);
    }

    public List<SchedulingDTO> findAllByPatientId(UUID id, Optional<LocalDate> date) {
        String cacheKey = buildCacheKey(PATIENT.name(), id, date.orElse(null));

        @SuppressWarnings("unchecked")
        List<SchedulingDTO> cachedResult = (List<SchedulingDTO>) cacheService.findByKey(cacheKey);

        if (cachedResult != null) {
            logger.info("Cache hit para chave: " + cacheKey);
            return cachedResult;
        }

        List<SchedulingDTO> result = date.map(d -> schedulingRepository.findAllByPatientIdAndDate(id, d))
                .orElseGet(() -> schedulingRepository.findAllByPatientId(id))
                .stream().map(SchedulingDTO::new).toList();

        cacheService.save(cacheKey, result);
        logger.info("Dados armazenados no cache para chave: " + cacheKey);
        return result;
    }

    public List<SchedulingDTO> findAllByProfessionalId(UUID id, Optional<LocalDate> date) {
        String cacheKey = buildCacheKey(PROFESSIONAL.name(), id, date.orElse(null));

        @SuppressWarnings("unchecked")
        List<SchedulingDTO> cachedResult = (List<SchedulingDTO>) cacheService.findByKey(cacheKey);

        if (cachedResult != null) {
            logger.info("Cache hit para chave: " + cacheKey);
            return cachedResult;
        }

        List<SchedulingDTO> result = date.map(d -> schedulingRepository.findAllByProfessionalIdAndDate(id, d))
                .orElseGet(() -> schedulingRepository.findAllByProfessionalId(id))
                .stream().map(SchedulingDTO::new).toList();

        cacheService.save(cacheKey, result);
        logger.info("Dados armazenados no cache para chave: " + cacheKey);
        return result;
    }

    public Scheduling findById(UUID id) {
        return schedulingRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Schedule not found"));
    }

    public SchedulingDTO done(UUID id) {
        Scheduling scheduling = findById(id);
        scheduling.done();
        schedulingRepository.save(scheduling);
        return new SchedulingDTO(scheduling);
    }

    /**
     * This method checks for late appointments and updates their status accordingly.
     * {@code @Scheduled(cron = "0 0/15 9-20 * * MON-SAT")} means this method will be executed every 15 minutes from Monday to Saturday, between 9 AM and 8 PM.
     */
    @Async
    @Transactional
    @Scheduled(cron = "0 0/15 9-20 * * MON-SAT")
    public void checkLateAppointments() {
        LocalDateTime now = LocalDateTime.now();
        List<Scheduling> lateSchedules = schedulingRepository
                .findAllByAppointmentsToDay(now.toLocalDate())
                .stream()
                .filter(scheduling -> scheduling.getAppointment().isBefore(now)
                        && (scheduling.hasStatus(SCHEDULED) || scheduling.hasStatus(RESCHEDULED)))
                .toList();
        lateSchedules.forEach(scheduling -> {
            scheduling.late();
            schedulingRepository.save(scheduling);
            //TODO: send notification to external service
            logger.info("Late appointment: " + scheduling);
        });
    }

    /**
     * This method checks for late schedules and updates their status to canceled.
     * {@code @Scheduled(cron = "0 0 9-20 * * MON-SAT")} means this method will be executed every hour from Monday to Saturday, between 9 AM and 8 PM.
     */
    @Async
    @Transactional
    @Scheduled(cron = "0 0 9-20 * * MON-SAT")
    public void checkLateSchedulesHourly() {
        List<Scheduling> lateSchedules = schedulingRepository.findAllByStatus(LATE);
        lateSchedules.forEach(scheduling -> {
            scheduling.cancel();
            schedulingRepository.save(scheduling);
            //TODO: send notification to external service
            logger.info("Canceled appointment: " + scheduling);
        });
    }

    /**
     * This method checks for appointments scheduled in the next 24 hours and calls an external service to send patient notification.
     * {@code @Scheduled(cron = "0 0 20 * * MON-SAT")} means this method will be executed once per day from Monday to Saturday, at 8 PM.
     */
    @Async
    @Transactional
    @Scheduled(cron = "0 0 20 * * MON-SAT")
    public void checkAppointmentsInNext24Hours() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next24Hours = now.plusHours(24);
        List<Scheduling> upcomingSchedules = schedulingRepository
                .findAllByAppointmentBetweenAndStatusIn(now, next24Hours, List.of(SCHEDULED, RESCHEDULED));
        upcomingSchedules.forEach(scheduling -> {
            // TODO: send notification to external service
            logger.info("Upcoming appointment in next 24 hours: " + scheduling);
        });
    }

    private String buildCacheKey(String type, UUID id, LocalDate date) {
        return date != null
                ? type + ":" + id + ":" + date
                : type + ":" + id;
    }
}
