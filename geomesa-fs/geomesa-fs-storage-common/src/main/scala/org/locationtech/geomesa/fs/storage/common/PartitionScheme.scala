/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.storage.common

import java.io.Serializable
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}
import java.util
import java.util.Date

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import com.vividsolutions.jts.geom.{Geometry, Point}
import org.geotools.data.DataAccessFactory.Param
import org.locationtech.geomesa.curve.Z2SFC
import org.locationtech.geomesa.filter.FilterHelper.extractGeometries
import org.locationtech.geomesa.filter.{FilterHelper, FilterValues}
import org.locationtech.geomesa.fs.storage.api.PartitionScheme
import org.locationtech.geomesa.utils.geotools.{GeometryUtils, WholeWorldPolygon}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.collection.JavaConversions._

object PartitionOpts {
  val DateTimeFormatOpt = "datetime-format"
  val StepUnitOpt = "step-unit"
  val StepOpt = "step"
  val DtgAttribute = "dtg-attribute"
  val GeomAttribute = "geom-attribute"
  val Z2Resolution = "z2-resolution"
  val LeafStorage = "leaf-storage"

  def parseDateTimeFormat(opts: Map[String, String]): String = {
    val fmtStr = opts(DateTimeFormatOpt)
    if (fmtStr.endsWith("/")) throw new IllegalArgumentException("Format cannot end with a slash")
    fmtStr
  }

  def parseDtgAttr(opts: Map[String, String]): String = {
    opts(DtgAttribute)
  }

  def parseGeomAttr(opts: Map[String, String]): String = {
    opts(GeomAttribute)
  }

  def parseStepUnit(opts: Map[String, String]): ChronoUnit = {
    ChronoUnit.valueOf(opts(StepUnitOpt).toUpperCase)
  }

  def parseStep(opts: Map[String, String]): Int = {
    opts(StepOpt).toInt
  }

  def parseZ2Resolution(opts: Map[String, String]): Int = {
    opts(Z2Resolution).toInt
  }
  def parseLeafStorage(opts: Map[String, String]): Boolean = {
    opts(LeafStorage).toBoolean
  }
}

object NamedPartitionSchemes {
  import DateTimeScheme.Formats._
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType._
  def build(name: String, sft: SimpleFeatureType): PartitionScheme = {
    val schemes = name.toLowerCase.split(',').map {
      case "year-jday" =>
        new DateTimeScheme(YearJDay, ChronoUnit.DAYS, 1, sft, sft.getDtgField.get, false)
      case "year-jday-hour" =>
        new DateTimeScheme(YearJDayHour, ChronoUnit.HOURS, 1, sft, sft.getDtgField.get, false)
      case "year-month-day" =>
        new DateTimeScheme(YearMonthDay, ChronoUnit.DAYS, 1, sft, sft.getDtgField.get, false)
      case "year-month-day-hour" =>
        new DateTimeScheme(YearMonthDayHour, ChronoUnit.HOURS, 1, sft, sft.getDtgField.get, false)
      case z2 if z2.matches("z2-[0-9]+bit") =>
        val bits = "z2-([0-9]+)bit".r("bits").findFirstMatchIn(z2).get.group("bits").toInt
        new Z2Scheme(bits, sft, sft.getGeomField, false)
    }
    if (schemes.length == 1) {
      schemes.head
    } else {
      new CompositeScheme(schemes.toSeq)
    }
  }
}


object PartitionScheme {
  // Must begin with GeoMesa in order to be persisted
  val PartitionSchemeKey = "geomesa.fs.partition-scheme.config"
  val PartitionOptsPrefix = "fs.partition-scheme.opts."
  val PartitionSchemeParam = new Param("fs.partition-scheme.name", classOf[String], "Partition scheme name", false)

  def addToSft(sft: SimpleFeatureType, scheme: PartitionScheme): Unit =
    sft.getUserData.put(PartitionSchemeKey, scheme.toString)

  def extractFromSft(sft: SimpleFeatureType): PartitionScheme = {
    if (!sft.getUserData.containsKey(PartitionSchemeKey)) {
      throw new IllegalArgumentException("SFT does not have partition scheme in hints")
    }
    apply(sft, sft.getUserData.get(PartitionSchemeKey).asInstanceOf[String])
  }

  def apply(sft: SimpleFeatureType, dsParams: util.Map[String, Serializable]): PartitionScheme = {
    val pName = PartitionSchemeParam.lookUp(dsParams).toString
    import scala.collection.JavaConversions._
    val pOpts = dsParams.keySet.filter(_.startsWith(PartitionOptsPrefix)).map { opt =>
      opt.replace(PartitionOptsPrefix, "") -> dsParams.get(opt).toString
    }.toMap
    PartitionScheme(sft, pName, pOpts)
  }

  // TODO delegate out, etc. make a loader, etc
  def apply(sft: SimpleFeatureType, pName: String, opts: Map[String, String]): PartitionScheme = {
    import PartitionOpts._
    val leaf = parseLeafStorage(opts)
    pName match {
      case "datetime" =>
        val attr = parseDtgAttr(opts)
        val fmt = parseDateTimeFormat(opts)
        val su = parseStepUnit(opts)
        val s = parseStep(opts)
        new DateTimeScheme(fmt, su, s, sft, attr, leaf)

      case "datetime-z2" =>
        val dtgAttr = parseDtgAttr(opts)
        val geomAttr = parseGeomAttr(opts)
        val fmt = parseDateTimeFormat(opts)
        val su = parseStepUnit(opts)
        val s = parseStep(opts)
        val z2Res = parseZ2Resolution(opts)
        new DateTimeZ2Scheme(fmt, su, s, z2Res, sft, dtgAttr, geomAttr, leaf)

      case "z2" =>
        val geomAttr = parseGeomAttr(opts)
        val z2Res = parseZ2Resolution(opts)
        new Z2Scheme(z2Res, sft, geomAttr, leaf)

      case _ =>
        throw new IllegalArgumentException(s"Unknown scheme name $pName")
    }
  }

  // TODO meh this sucks
  def apply(sft: SimpleFeatureType, conf: Config): PartitionScheme = {
    if (!conf.hasPath("name")) throw new IllegalArgumentException("config must have name for scheme")
    if (!conf.hasPath("opts")) throw new IllegalArgumentException("config must have opts for scheme")

    val name = conf.getString("name")
    val optConf = conf.getConfig("opts")
    val opts = conf.getConfig("opts").entrySet().map { e =>
        e.getKey -> optConf.getString(e.getKey)
    }.toMap

    apply(sft, name, opts)
  }

  def apply(sft: SimpleFeatureType, conf: String): PartitionScheme = {
    apply(sft, ConfigFactory.parseString(conf))
  }

}

class DateTimeScheme(fmtStr: String,
                     stepUnit: ChronoUnit,
                     step: Int,
                     sft: SimpleFeatureType,
                     dtgAttribute: String,
                     leafStorage: Boolean)
  extends PartitionScheme {
  private val index = sft.indexOf(dtgAttribute)
  private val fmt = DateTimeFormatter.ofPattern(fmtStr)
  override def getPartitionName(sf: SimpleFeature): String = {
    val instant = sf.getAttribute(index).asInstanceOf[Date].toInstant.atZone(ZoneOffset.UTC)
    fmt.format(instant)
  }

  override def getCoveringPartitions(f: Filter): java.util.List[String] = {
    val intervals = FilterHelper.extractIntervals(f, dtgAttribute).values.map { case (s,e) =>
      (Instant.ofEpochMilli(s.getMillis).atZone(ZoneOffset.UTC), Instant.ofEpochMilli(e.getMillis).atZone(ZoneOffset.UTC))
    }
    intervals.flatMap { case (start, end) =>
      val count = start.until(end, stepUnit).toInt
      Seq.tabulate(count)(i => fmt.format(start.plus(step * i, stepUnit)))
    }
  }

  // ? is this a good idea
  override def maxDepth(): Int = fmtStr.count(_ == '/')

  override def toString: String = {
    import PartitionOpts._

    import scala.collection.JavaConverters._
    val conf = ConfigFactory.parseMap(Map(
      "name" -> "datetime",
      "opts" -> Map(
        DtgAttribute -> dtgAttribute,
        DateTimeFormatOpt -> fmtStr,
        StepUnitOpt -> stepUnit.toString,
        StepOpt -> step.toString,
        LeafStorage -> leafStorage.toString).asJava).asJava)
    conf.root().render(ConfigRenderOptions.concise)
  }

  override def fromString(sft: SimpleFeatureType, s: String): PartitionScheme =
    PartitionScheme(sft, ConfigFactory.parseString(s))

  override def isLeafStorage: Boolean = leafStorage
}

object DateTimeScheme {
  object Formats {
    val YearJDay = "yyyy/DDD"
    val YearJDayHour = "yyyy/DDD/HH"
    val YearMonthDay = "yyyy/MM/dd"
    val YearMonthDayHour = "yyyy/MM/dd/HH"
  }
}

class Z2Scheme(bits: Int, // number of bits
               sft: SimpleFeatureType,
               geomAttribute: String,
               leafStorage: Boolean) extends PartitionScheme {

  require(bits % 2 == 0, "Resolution must be an even number")

  // note: z2sfc resolution is per dimension
  private val z2 = new Z2SFC(bits / 2)
  private val digits = math.ceil(math.log10(math.pow(2, bits))).toInt
  private val geomAttrIndex = sft.indexOf(geomAttribute)

  override def getPartitionName(sf: SimpleFeature): String = {
    val pt = sf.getAttribute(geomAttrIndex).asInstanceOf[Point]
    val idx = z2.index(pt.getX, pt.getY).z
    idx.formatted(s"%0${digits}d")
  }

  override def getCoveringPartitions(f: Filter): java.util.List[String] = {
    // TODO trim ranges based on files that actually exist...
    val geometries: FilterValues[Geometry] = {
      // TODO support something other than point geoms
      val extracted = extractGeometries(f, geomAttribute, true)
      if (extracted.nonEmpty) { extracted } else { FilterValues(Seq(WholeWorldPolygon)) }
    }

    if (geometries.disjoint) {
      return List.empty[String]
    }

    val xy = geometries.values.map(GeometryUtils.bounds)
    val ranges = z2.ranges(xy)
    val enumerations = ranges.flatMap(ir => ir.lower to ir.upper)
    enumerations.map(_.formatted(s"%0${digits}d"))
  }

  override def maxDepth(): Int = 1

  override def toString: String = {
    import PartitionOpts._

    import scala.collection.JavaConverters._
    val conf = ConfigFactory.parseMap(Map(
      "name" -> "z2",
      "opts" -> Map(
        GeomAttribute -> geomAttribute,
        Z2Resolution -> bits.toString,
        LeafStorage -> leafStorage.toString).asJava))
    conf.root().render(ConfigRenderOptions.concise)
  }

  override def fromString(sft: SimpleFeatureType, s: String): PartitionScheme =
    PartitionScheme(sft, ConfigFactory.parseString(s))

  override def isLeafStorage: Boolean = leafStorage

}

class DateTimeZ2Scheme(fmtStr: String,
                       stepUnit: ChronoUnit,
                       step: Int,
                       resolution: Int,
                       sft: SimpleFeatureType,
                       dtgAttribute: String,
                       geomAttribute: String,
                       leafStorage: Boolean) extends PartitionScheme {

  private val z2Scheme = new Z2Scheme(resolution, sft, geomAttribute, leafStorage)
  private val dateScheme = new DateTimeScheme(fmtStr, stepUnit, step, sft, dtgAttribute, leafStorage)

  override def getPartitionName(sf: SimpleFeature): String = {
    dateScheme.getPartitionName(sf) + "/" + z2Scheme.getPartitionName(sf)
  }

  override def getCoveringPartitions(f: Filter): java.util.List[String] = {
    import scala.collection.JavaConversions._
    val dateParts = dateScheme.getCoveringPartitions(f)
    val z2Parts = z2Scheme.getCoveringPartitions(f)
    for { d <- dateParts; z <- z2Parts } yield { s"$d/$z" }
  }

  override def maxDepth(): Int = z2Scheme.maxDepth() + dateScheme.maxDepth()

  override def toString: String = {
    import PartitionOpts._

    import scala.collection.JavaConverters._
    val conf = ConfigFactory.parseMap(Map(
      "name" -> "datetime-z2",
      "opts" -> Map(
        GeomAttribute -> geomAttribute,
        Z2Resolution -> resolution.toString,
        DtgAttribute -> dtgAttribute,
        DateTimeFormatOpt -> fmtStr,
        StepUnitOpt -> stepUnit.toString,
        StepOpt -> step.toString,
        LeafStorage -> leafStorage.toString).asJava).asJava)
    conf.root().render(ConfigRenderOptions.concise)
  }

  override def fromString(sft: SimpleFeatureType, s: String): PartitionScheme =
    PartitionScheme(sft, ConfigFactory.parseString(s))

  override def isLeafStorage: Boolean = leafStorage

}

class CompositeScheme(schemes: Seq[PartitionScheme]) extends PartitionScheme {

  override def getPartitionName(sf: SimpleFeature): String = schemes.map(_.getPartitionName(sf)).mkString("/")

  override def getCoveringPartitions(f: Filter): util.List[String] =
    schemes.map(_.getCoveringPartitions(f)).reduce((a, b) => for (i <- a; j <-b) yield { s"$i/$j"})

  override def maxDepth(): Int = schemes.map(_.maxDepth()).sum

  override def isLeafStorage: Boolean = schemes.forall(_.isLeafStorage)

  override def fromString(sft: SimpleFeatureType, s: String): PartitionScheme = ???
}