package com.melonbase.microquark.repo

import com.melonbase.microquark.microstream.getDataRoot
import com.melonbase.microquark.repo.data.Kanton
import com.melonbase.microquark.repo.data.Volksabstimmung
import com.melonbase.microquark.repo.data.Vorlage
import com.melonbase.microquark.repo.data.Wahlresultat
import com.melonbase.microquark.rest.dto.inbound.NeueVolksabstimmung
import com.melonbase.microquark.rest.dto.outbound.Resultat
import com.melonbase.microquark.rest.dto.outbound.VolksabstimmungResultat
import com.melonbase.microquark.rest.dto.outbound.VorlageResultat
import com.melonbase.microquark.rest.mapping.mapToDto
import com.melonbase.microquark.service.NotFoundResult
import com.melonbase.microquark.service.RejectedResult
import com.melonbase.microquark.service.ServiceResult
import com.melonbase.microquark.service.SuccessResult
import com.melonbase.microquark.service.SuccessWithDataResult
import mu.KotlinLogging
import one.microstream.storage.types.StorageManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.enterprise.context.ApplicationScoped
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.random.Random

private val log = KotlinLogging.logger {}

@ApplicationScoped
class ElectionsRepo @Inject constructor(private val storage: StorageManager) {

  private val readWriteLock = ReentrantReadWriteLock()
  private val read = readWriteLock.readLock()
  private val write = readWriteLock.writeLock()

  fun getVolksabstimmungen(): Set<com.melonbase.microquark.rest.dto.outbound.Volksabstimmung> {
    read.withLock {
      return storage.getDataRoot().volksabstimmungen.mapToDto()
    }
  }

  fun getVolksabstimmung(datum: LocalDate): com.melonbase.microquark.rest.dto.outbound.Volksabstimmung? {
    read.withLock {
      return getVolksabstimmungUnlocked(datum)?.mapToDto()
    }
  }

  fun addVolksabstimmung(volksabstimmung: NeueVolksabstimmung): ServiceResult<com.melonbase.microquark.rest.dto.outbound.Volksabstimmung> {
    write.withLock {
      if (getVolksabstimmung(volksabstimmung.datum) != null) {
        return RejectedResult("Es existiert bereits eine Volksabstimmung am '${volksabstimmung.datum}'.")
      }

      val neueVolksabstimmung = Volksabstimmung(
        volksabstimmung.datum,
        volksabstimmung.vorlagen.map { vorlage -> Vorlage(vorlage) }
      )

      val root = storage.getDataRoot()
      root.volksabstimmungen.add(neueVolksabstimmung)
      storage.store(root.volksabstimmungen)

      return SuccessWithDataResult(neueVolksabstimmung.mapToDto())
    }
  }

  fun deleteVolksabstimmung(datum: LocalDate): ServiceResult<Nothing> {
    write.withLock {
      val successful = storage.getDataRoot().volksabstimmungen.removeIf { v -> v.datum == datum }
      if (successful) {
        storage.store(storage.getDataRoot().volksabstimmungen)
        return SuccessResult
      }
      return NotFoundResult
    }
  }

  fun performAbstimmung(datum: LocalDate): ServiceResult<Nothing> {
    write.withLock {
      val volksabstimmung = getVolksabstimmungUnlocked(datum) ?: return NotFoundResult

      val gewaehlt = volksabstimmung.vorlagen.any { it.wahlresultat != null }
      if (gewaehlt) {
        return RejectedResult("Es wurde bereits abgestimmt.")
      }

      volksabstimmung.vorlagen.forEach { vorlage ->
        log.info("Abstimmung läuft zu: ${vorlage.beschreibung}")

        val stimmenByKanton = Kanton.values().map { kanton ->
          val stimmbeteiligung = Random.nextDouble(10.0, 100.0).toBigDecimal().setScale(2, RoundingMode.HALF_DOWN)
          val numVoters = (kanton.einwohner * stimmbeteiligung.toDouble() / 100.0).roundToInt()

          val schwelle = Random.nextDouble(0.0, 100.0)
          val ergebnis = List(numVoters) { Random.nextDouble(0.0, 100.0) > schwelle }

          Pair(kanton, ergebnis)
        }.toMap()

        vorlage.wahlresultat = Wahlresultat(stimmenByKanton)
        storage.store(vorlage)
      }
    }
    return SuccessResult
  }

  fun getResult(datum: LocalDate): ServiceResult<VolksabstimmungResultat> {
    read.withLock {
      val volksabstimmung = getVolksabstimmungUnlocked(datum) ?: return NotFoundResult

      val nochNichtAbgestimmt =
        volksabstimmung.vorlagen.any { it.wahlresultat == null }
      if (nochNichtAbgestimmt) {
        return RejectedResult("Abstimmung wurde noch nicht durchgeführt.")
      }

      val vorlageResultate = volksabstimmung.vorlagen.map { vorlage ->
        val kantonsresultate = vorlage.wahlresultat!!.getStimmenByKanton().map { (kanton, stimmen) ->
          val einwohner = kanton.einwohner
          val abgegebeneStimmen = stimmen.size
          val wahlbeteiligungProzent = BigDecimal.valueOf(100.0 / einwohner * abgegebeneStimmen)
            .setScale(2, RoundingMode.HALF_EVEN)

          val jaStimmen = stimmen.count { it }
          val neinStimmen = abgegebeneStimmen - jaStimmen

          val jaProzent = BigDecimal.valueOf(100.0 / abgegebeneStimmen * jaStimmen)
            .setScale(2, RoundingMode.HALF_EVEN)
          val neinProzent = BigDecimal.valueOf(100) - jaProzent

          val resultat = Resultat.Builder()
            .einwohner(einwohner)
            .abgegebeneStimmen(abgegebeneStimmen)
            .wahlbeteiligungProzent(wahlbeteiligungProzent)
            .jaStimmen(jaStimmen)
            .neinStimmen(neinStimmen)
            .jaProzent(jaProzent)
            .neinProzent(neinProzent)
            .build()

          Pair(kanton, resultat)
        }.toMap()

        val bundesresultat = calculateBundesresultat(vorlage)

        VorlageResultat(vorlage.beschreibung, bundesresultat, kantonsresultate)
      }

      return SuccessWithDataResult(VolksabstimmungResultat(datum, vorlageResultate))
    }
  }

  private fun calculateBundesresultat(vorlage: Vorlage): Resultat {
    val gesamtEinwohner = vorlage.wahlresultat!!.getStimmenByKanton().map { (kanton, _) ->
      kanton.einwohner
    }.sum()
    val gesamtAbgegebeneStimmen = vorlage.wahlresultat!!.getStimmenByKanton().map { (_, stimmen) ->
      stimmen.size
    }.sum()
    val gesamtWahlbeteiligungProzent = BigDecimal.valueOf(100.0 / gesamtEinwohner * gesamtAbgegebeneStimmen)
      .setScale(2, RoundingMode.HALF_EVEN)

    val gesamtJaStimmen = vorlage.wahlresultat!!.getStimmenByKanton().map { (_, stimmen) ->
      stimmen.count { it }
    }.sum()
    val gesamtNeinStimmen = gesamtAbgegebeneStimmen - gesamtJaStimmen

    val gesamtJaProzent = BigDecimal.valueOf(100.0 / gesamtAbgegebeneStimmen * gesamtJaStimmen)
      .setScale(2, RoundingMode.HALF_EVEN)
    val gesamtNeinProzent = BigDecimal.valueOf(100) - gesamtJaProzent

    return Resultat.Builder()
      .einwohner(gesamtEinwohner)
      .abgegebeneStimmen(gesamtAbgegebeneStimmen)
      .wahlbeteiligungProzent(gesamtWahlbeteiligungProzent)
      .jaStimmen(gesamtJaStimmen)
      .neinStimmen(gesamtNeinStimmen)
      .jaProzent(gesamtJaProzent)
      .neinProzent(gesamtNeinProzent)
      .build()
  }

  private fun getVolksabstimmungUnlocked(datum: LocalDate): Volksabstimmung? {
    return storage.getDataRoot().volksabstimmungen.find { it.datum == datum }
  }
}